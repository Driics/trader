package ru.driics.aitrade.common.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Extension property for easy logger access in classes.
 * Usage: `private val log = logger()` in companion object
 */
inline fun <reified T> logger(): KLogger = KotlinLogging.logger(T::class.java.simpleName)

/**
 * Extension function for companion objects to get logger.
 * Usage: `private val log = logger()` in companion object
 */
inline fun <reified T> T.logger(): KLogger = KotlinLogging.logger(T::class.java.simpleName)

