package ru.driics.aitrade.domain.ports

interface PromptOutputPort {
    fun write(prompt: String): Boolean
    fun print(prompt: String)
}