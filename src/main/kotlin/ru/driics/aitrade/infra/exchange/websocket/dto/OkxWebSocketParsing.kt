package ru.driics.aitrade.infra.exchange.websocket.dto

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

inline fun <reified T> ObjectMapper.readWsEnvelope(json: String): OkxWsEnvelope<T> =
    this.readValue(json, object : TypeReference<OkxWsEnvelope<T>>() {})