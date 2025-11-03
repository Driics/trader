package ru.driics.aitrade.infra.prompt

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.driics.aitrade.domain.ports.PromptOutputPort
import java.io.File

@Service
class FilePromptOutputAdapter(
    @param:Value($$"${prompt.output-path:./prompt.txt}") private val path: String
): PromptOutputPort {
    override fun write(prompt: String): Boolean {
        val tmp = File("$path.tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(prompt)
        return tmp.renameTo(File(path))
    }

    override fun print(prompt: String) = println(prompt)
}