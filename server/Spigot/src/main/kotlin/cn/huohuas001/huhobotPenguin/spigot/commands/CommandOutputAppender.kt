package cn.huohuas001.huhobotPenguin.spigot.commands

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import java.util.concurrent.CopyOnWriteArrayList

class CommandOutputAppender private constructor() : AbstractAppender(
    "CommandOutputAppender",
    null,
    PatternLayout.createDefaultLayout(),
    true,
    Property.EMPTY_ARRAY
) {
    private val messages = CopyOnWriteArrayList<String>()

    @Volatile
    private var capturing = false

    init {
        start()
    }

    override fun append(event: LogEvent) {
        if (capturing) {
            messages.add(event.message.formattedMessage)
        }
    }

    fun startCapture() {
        messages.clear()
        capturing = true
    }

    fun stopCapture(): List<String> {
        capturing = false
        return messages.toList()
    }

    companion object {
        private var instance: CommandOutputAppender? = null

        fun getInstance(): CommandOutputAppender {
            val currentInstance = instance
            if (currentInstance != null) {
                return currentInstance
            }

            return CommandOutputAppender().also { appender ->
                val rootLogger = LogManager.getRootLogger() as Logger
                rootLogger.addAppender(appender)
                instance = appender
            }
        }

        fun removeInstance() {
            instance?.let { appender ->
                val rootLogger = LogManager.getRootLogger() as Logger
                rootLogger.removeAppender(appender)
                appender.stop()
            }
            instance = null
        }
    }
}
