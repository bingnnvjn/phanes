package com.gph.fable.shared.shell

import com.gph.fable.shared.logger.Logger
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/** Continuously consumes a process stream without blocking the process pipe. */
class StreamGobbler : Thread {
    fun interface OnLineListener {
        fun onLine(line: String)
    }

    fun interface OnStreamClosedListener {
        fun onStreamClosed()
    }

    private val shell: String
    private val inputStream: InputStream
    private val reader: BufferedReader
    private val listWriter: MutableList<String>?
    private val stringWriter: StringBuilder?
    private val lineListener: OnLineListener?
    private val streamClosedListener: OnStreamClosedListener?
    private val logLevel: Int?
    private val stateLock = java.lang.Object()
    @Volatile private var active = true
    @Volatile private var calledOnClose = false

    constructor(shell: String, inputStream: InputStream, outputList: MutableList<String>?, logLevel: Int?) :
        super("Gobbler#${incThreadCounter()}") {
        this.shell = shell
        this.inputStream = inputStream
        this.reader = BufferedReader(InputStreamReader(inputStream))
        this.listWriter = outputList
        this.stringWriter = null
        this.lineListener = null
        this.streamClosedListener = null
        this.logLevel = logLevel
    }

    constructor(shell: String, inputStream: InputStream, outputString: StringBuilder?, logLevel: Int?) :
        super("Gobbler#${incThreadCounter()}") {
        this.shell = shell
        this.inputStream = inputStream
        this.reader = BufferedReader(InputStreamReader(inputStream))
        this.listWriter = null
        this.stringWriter = outputString
        this.lineListener = null
        this.streamClosedListener = null
        this.logLevel = logLevel
    }

    constructor(
        shell: String,
        inputStream: InputStream,
        onLineListener: OnLineListener?,
        onStreamClosedListener: OnStreamClosedListener?,
        logLevel: Int?
    ) : super("Gobbler#${incThreadCounter()}") {
        this.shell = shell
        this.inputStream = inputStream
        this.reader = BufferedReader(InputStreamReader(inputStream))
        this.listWriter = null
        this.stringWriter = null
        this.lineListener = onLineListener
        this.streamClosedListener = onStreamClosedListener
        this.logLevel = logLevel
    }

    override fun run() {
        val defaultLogTag = Logger.getDefaultLogTag()
        val loggingEnabled = Logger.shouldEnableLoggingForCustomLogLevel(logLevel)
        if (loggingEnabled) {
            Logger.logVerbose(
                LOG_TAG,
                "Using custom log level: $logLevel, current log level: ${Logger.getLogLevel()}"
            )
        }

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (loggingEnabled) {
                    Logger.logVerboseForce(
                        "$defaultLogTag${"Command"}",
                        String.format(Locale.ENGLISH, "[%s] %s", shell, line)
                    )
                }
                stringWriter?.append(line)?.append("\n")
                listWriter?.add(line)
                lineListener?.onLine(line)
                while (!active) {
                    synchronized(stateLock) {
                        try {
                            stateLock.wait(128)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            }
        } catch (_: java.io.IOException) {
            notifyStreamClosed()
        }

        try {
            reader.close()
        } catch (_: java.io.IOException) {
        }
        notifyStreamClosed()
    }

    fun resumeGobbling() {
        if (!active) synchronized(stateLock) {
            active = true
            stateLock.notifyAll()
        }
    }

    fun suspendGobbling() {
        synchronized(stateLock) {
            active = false
            stateLock.notifyAll()
        }
    }

    fun waitForSuspend() {
        synchronized(stateLock) {
            while (active) {
                try {
                    stateLock.wait(32)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    fun isSuspended(): Boolean = synchronized(stateLock) { !active }

    fun getInputStream(): InputStream = inputStream

    fun getOnLineListener(): OnLineListener? = lineListener

    internal fun conditionalJoin() {
        if (calledOnClose || Thread.currentThread() === this) return
        join()
    }

    private fun notifyStreamClosed() {
        if (!calledOnClose) {
            calledOnClose = true
            streamClosedListener?.onStreamClosed()
        }
    }

    companion object {
        private var threadCounter = 0
        private const val LOG_TAG = "StreamGobbler"

        @Synchronized
        private fun incThreadCounter(): Int = threadCounter++
    }
}
