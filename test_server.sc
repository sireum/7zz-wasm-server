#!/usr/bin/env -S scala-cli shebang
//> using scala 2.13
//> using jvm graalvm-community:25.0.2
//> using javaOpt --enable-native-access=ALL-UNNAMED
//> using dep org.graalvm.polyglot:polyglot:25.0.2
//> using dep org.graalvm.wasm:wasm-language:25.0.2
//> using dep org.graalvm.truffle:truffle-runtime:25.0.2
// Test 7zz WASM server protocol via GraalWasm (in-process).
//
// Usage:
//     scala-cli test_server.sc -- [7zz_server_graal.wasm]

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.graalvm.polyglot.{Context, Engine, Source, PolyglotException}
import org.graalvm.polyglot.io.ByteSequence

// --- Config ---

val scriptDir = {
  val p = Paths.get(sys.props.getOrElse("script.dir",
    sys.props.getOrElse("user.dir", ".")))
  p.toAbsolutePath
}
val wasmPath = if (args.nonEmpty) Paths.get(args(0)).toAbsolutePath
               else scriptDir.resolve("7zz_server_graal.wasm")

if (!Files.exists(wasmPath)) {
  System.err.println(s"WASM binary not found: $wasmPath")
  sys.exit(1)
}

val wasmBytes = Files.readAllBytes(wasmPath)
println(s"Binary: $wasmPath (${wasmBytes.length / 1024} KiB)")

// --- Protocol helpers ---

def writeU32(os: OutputStream, v: Int): Unit = {
  os.write((v >> 24) & 0xFF)
  os.write((v >> 16) & 0xFF)
  os.write((v >> 8) & 0xFF)
  os.write(v & 0xFF)
  os.flush()
}

def readU32(is: InputStream): Int = {
  val buf = new Array[Byte](4)
  var n = 0
  while (n < 4) {
    val r = is.read(buf, n, 4 - n)
    if (r < 0) return -1
    n += r
  }
  ((buf(0) & 0xFF) << 24) | ((buf(1) & 0xFF) << 16) |
    ((buf(2) & 0xFF) << 8) | (buf(3) & 0xFF)
}

def readI32(is: InputStream): Int = readU32(is)

def writeStr(os: OutputStream, s: String): Unit = {
  val bytes = s.getBytes(StandardCharsets.UTF_8)
  writeU32(os, bytes.length)
  os.write(bytes)
  os.flush()
}

def readBytes(is: InputStream, n: Int): Array[Byte] = {
  if (n <= 0) return Array.emptyByteArray
  val buf = new Array[Byte](n)
  var read = 0
  while (read < n) {
    val r = is.read(buf, read, n - read)
    if (r < 0) return buf.take(read)
    read += r
  }
  buf
}

// --- Ring buffer pipe (like FastPipe in Smt2WasmInvoke_Ext) ---

class FastPipe(capacity: Int = 1024 * 1024) {
  private val buf = new Array[Byte](capacity)
  private var readPos = 0
  private var writePos = 0
  private var count = 0
  @volatile private var closed = false
  private val lock = new java.util.concurrent.locks.ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val notFull = lock.newCondition()

  val inputStream: InputStream = new InputStream {
    override def read(): Int = {
      lock.lock()
      try {
        while (count == 0 && !closed) notEmpty.await()
        if (count == 0) return -1
        val b = buf(readPos) & 0xFF
        readPos = (readPos + 1) % capacity
        count -= 1
        notFull.signal()
        b
      } finally { lock.unlock() }
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      if (len == 0) return 0
      lock.lock()
      try {
        while (count == 0 && !closed) notEmpty.await()
        if (count == 0) return -1
        val n = math.min(len, count)
        var i = 0
        while (i < n) {
          b(off + i) = buf(readPos)
          readPos = (readPos + 1) % capacity
          i += 1
        }
        count -= n
        notFull.signal()
        n
      } finally { lock.unlock() }
    }
  }

  val outputStream: OutputStream = new OutputStream {
    override def write(b: Int): Unit = {
      lock.lock()
      try {
        while (count == capacity && !closed) notFull.await()
        if (closed) throw new java.io.IOException("Pipe closed")
        buf(writePos) = b.toByte
        writePos = (writePos + 1) % capacity
        count += 1
        notEmpty.signal()
      } finally { lock.unlock() }
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      if (len == 0) return
      lock.lock()
      try {
        var written = 0
        while (written < len) {
          while (count == capacity && !closed) notFull.await()
          if (closed) throw new java.io.IOException("Pipe closed")
          val space = capacity - count
          val n = math.min(len - written, space)
          var i = 0
          while (i < n) {
            buf(writePos) = b(off + written + i)
            writePos = (writePos + 1) % capacity
            i += 1
          }
          count += n
          written += n
          notEmpty.signal()
        }
      } finally { lock.unlock() }
    }

    override def flush(): Unit = {}
  }

  def close(): Unit = {
    lock.lock()
    try {
      closed = true
      notEmpty.signalAll()
      notFull.signalAll()
    } finally { lock.unlock() }
  }

  def reset(): Unit = {
    lock.lock()
    try {
      readPos = 0
      writePos = 0
      count = 0
      closed = false
    } finally { lock.unlock() }
  }
}

// --- Server runner ---

case class RunResult(exitCode: Int, stdout: String, stderr: String)

class Server(wasmBytes: Array[Byte]) {
  private val stdinPipe = new FastPipe()
  private val stdoutPipe = new FastPipe()
  private val engine = Engine.newBuilder("wasm")
    .option("engine.WarnInterpreterOnly", "false")
    .build()
  private val source = Source.newBuilder("wasm",
    ByteSequence.create(wasmBytes), "7zz_server").build()
  @volatile private var alive = false
  @volatile private var error: String = _
  private var thread: Thread = _

  start()

  private def start(): Unit = {
    stdinPipe.reset()
    stdoutPipe.reset()
    alive = false
    error = null

    thread = new Thread(() => {
      var context: Context = null
      try {
        context = Context.newBuilder("wasm")
          .engine(engine)
          .option("wasm.Builtins", "wasi_snapshot_preview1")
          .option("wasm.WasiMapDirs", "/::/")
          .arguments("wasm", Array("7zz_server"))
          .in(stdinPipe.inputStream)
          .out(stdoutPipe.outputStream)
          .err(System.err)
          .allowAllAccess(true)
          .build()

        val module = context.eval(source)
        val instance = module.newInstance()
        val exports = instance.getMember("exports")
        val startFn = exports.getMember("_start")
        alive = true
        try {
          startFn.executeVoid()
        } catch {
          case e: PolyglotException if e.isExit && e.getExitStatus == 0 =>
          case e: PolyglotException if e.isExit =>
            error = s"Server exited with code ${e.getExitStatus}"
          case e: Throwable =>
            error = s"WASM error: ${e.getMessage}"
        }
      } catch {
        case e: Throwable =>
          error = s"Context error: ${e.getMessage}"
      } finally {
        alive = false
        try { stdoutPipe.close() } catch { case _: Throwable => }
        if (context != null) {
          try { context.close() } catch { case _: Throwable => }
        }
      }
    })
    thread.setDaemon(true)
    thread.setName("7zz-wasm-server")
    thread.start()

    // Wait for server to initialize
    val deadline = System.currentTimeMillis() + 30000
    while (!alive && error == null && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    if (!alive && error == null) {
      error = "Server startup timed out"
    }
  }

  def isAlive: Boolean = alive && error == null
  def lastError: Option[String] = Option(error)

  /** Send a command to the server and read the response. */
  def run(workDir: Path, cmdArgs: String*): RunResult = {
    val os = stdinPipe.outputStream
    val is = stdoutPipe.inputStream

    // Send working directory
    writeStr(os, workDir.toAbsolutePath.toString)

    // Send args (prepend "7zz" as argv[0])
    val allArgs = "7zz" +: cmdArgs
    writeU32(os, allArgs.length)
    for (arg <- allArgs) writeStr(os, arg)
    os.flush()

    // Read response
    val exitCode = readI32(is)
    val outLen = readU32(is)
    val outBytes = readBytes(is, outLen)
    val errLen = readU32(is)
    val errBytes = readBytes(is, errLen)

    RunResult(exitCode,
      new String(outBytes, StandardCharsets.UTF_8),
      new String(errBytes, StandardCharsets.UTF_8))
  }

  def shutdown(): Unit = {
    try {
      val os = stdinPipe.outputStream
      writeStr(os, "")  // empty workDir
      writeU32(os, 0)   // argc=0 = shutdown
      os.flush()
    } catch { case _: Throwable => }
    stdinPipe.close()
    stdoutPipe.close()
    try { thread.join(5000) } catch { case _: Throwable => }
    if (thread.isAlive) thread.interrupt()
  }
}

// --- Test helpers ---

var passed = 0
var failed = 0

def test(label: String)(body: => Boolean): Unit = {
  print(s"  $label ... ")
  val t0 = System.nanoTime()
  try {
    if (body) {
      val ms = (System.nanoTime() - t0) / 1000000L
      println(s"PASS  (${ms}ms)")
      passed += 1
    } else {
      println("FAIL")
      failed += 1
    }
  } catch {
    case e: Throwable =>
      println(s"FAIL (${e.getMessage})")
      failed += 1
  }
}

// --- Tests ---

println("\n=== Starting 7zz WASM server ===")
val server = new Server(wasmBytes)
println(s"Server alive: ${server.isAlive}")
server.lastError.foreach(e => println(s"Server error: $e"))

val tmpDir = Files.createTempDirectory("7zz_server_test")
val testFile = tmpDir.resolve("hello.txt")
val archivePath = tmpDir.resolve("test.7z")
val extractDir = tmpDir.resolve("extracted")
Files.createDirectories(extractDir)
Files.write(testFile, "Hello from 7zz WASM server test!\n".getBytes(StandardCharsets.UTF_8))

println("\n=== Test: 7zz server protocol ===")

test("Server is alive") {
  server.isAlive
}

test("Help output") {
  val r = server.run(tmpDir)
  r.stdout.contains("7-Zip") && r.stdout.contains("Usage:")
}

test("Archive creation") {
  val r = server.run(tmpDir, "a", "-y", archivePath.toString, testFile.toString)
  r.exitCode == 0 && r.stdout.contains("Everything is Ok")
}

test("Archive listing") {
  val r = server.run(tmpDir, "l", archivePath.toString)
  r.exitCode == 0 && r.stdout.contains("hello.txt")
}

test("Archive extraction") {
  val r = server.run(tmpDir, "x", "-y", archivePath.toString,
    s"-o${extractDir.toAbsolutePath}")
  r.exitCode == 0 && r.stdout.contains("Everything is Ok")
}

test("Extracted content matches") {
  val extracted = extractDir.resolve("hello.txt")
  Files.exists(extracted) &&
    new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8) ==
      "Hello from 7zz WASM server test!\n"
}

test("Multiple operations (server stays alive)") {
  val rtDir = tmpDir.resolve("roundtrip")
  val rtExtract = tmpDir.resolve("rt_extract")
  val rtArchive = tmpDir.resolve("roundtrip.7z")
  Files.createDirectories(rtDir)
  Files.createDirectories(rtExtract)
  for (i <- 1 to 5) {
    Files.write(rtDir.resolve(s"file_$i.txt"),
      s"Content of file $i\n${(1 to i * 100).mkString(",")}\n"
        .getBytes(StandardCharsets.UTF_8))
  }
  val ra = server.run(tmpDir, "a", "-y", rtArchive.toString,
    s"${rtDir.toAbsolutePath}/*")
  if (ra.exitCode != 0) false
  else {
    val rx = server.run(tmpDir, "x", "-y", rtArchive.toString,
      s"-o${rtExtract.toAbsolutePath}")
    if (rx.exitCode != 0) false
    else {
      (1 to 5).forall { i =>
        val orig = Files.readAllBytes(rtDir.resolve(s"file_$i.txt"))
        val ext = rtExtract.resolve(s"file_$i.txt")
        Files.exists(ext) && java.util.Arrays.equals(orig,
          Files.readAllBytes(ext))
      }
    }
  }
}

test("Delete from archive") {
  val delDir = tmpDir.resolve("deltest")
  Files.createDirectories(delDir)
  Files.write(delDir.resolve("keep.txt"), "keep\n".getBytes(StandardCharsets.UTF_8))
  Files.write(delDir.resolve("remove.txt"), "remove\n".getBytes(StandardCharsets.UTF_8))
  val delArchive = tmpDir.resolve("deltest.7z")
  val ra = server.run(tmpDir, "a", "-y", delArchive.toString,
    delDir.resolve("keep.txt").toString, delDir.resolve("remove.txt").toString)
  if (ra.exitCode != 0) false
  else {
    // Delete remove.txt from archive
    val rd = server.run(tmpDir, "d", "-y", delArchive.toString,
      delDir.resolve("remove.txt").toString)
    if (rd.exitCode != 0) false
    else {
      // List should show only keep.txt
      val rl = server.run(tmpDir, "l", delArchive.toString)
      rl.exitCode == 0 && rl.stdout.contains("keep.txt") && !rl.stdout.contains("remove.txt")
    }
  }
}

// --- Shutdown & Summary ---

println("\nShutting down server ...")
server.shutdown()

println(s"\n$passed passed, $failed failed")

// Cleanup
def deleteRecursive(p: Path): Unit = {
  if (Files.isDirectory(p))
    Files.list(p).forEach(deleteRecursive)
  Files.deleteIfExists(p)
}
deleteRecursive(tmpDir)

sys.exit(if (failed == 0) 0 else 1)
