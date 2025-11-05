package ru.driics.aitrade.infra.prompt

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.driics.aitrade.domain.ports.PromptOutputPort
import java.io.File

@Service
class FilePromptOutputAdapter(
    @param:Value($$"${prompt.output-path:./prompt.txt}")
    private val path: String
) : PromptOutputPort {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun write(prompt: String): Boolean = try {
        val target = File(path)
        val tmp = File("$path.tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(prompt)

        val success = tmp.renameTo(target) || run {
            tmp.copyTo(target, overwrite = true)

            val deleted = tmp.delete()
            if (!deleted)
                log.warn { "Failed to delete temp file: ${tmp.absolutePath}" }

            true
        }

        if (!success) log.warn { "Failed to write prompt to $path" }

        success
    } catch (e: Exception) {
        log.error(e) { "Failed to write prompt to $path" }
        false
    }

    override fun print(prompt: String) {
        // Intentionally empty - we don't print prompts anymore
        // Logging is handled by use cases
    }
}