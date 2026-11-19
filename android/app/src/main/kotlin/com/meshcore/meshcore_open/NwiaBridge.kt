package com.meshcore.meshcore_open

import android.content.Context
import com.nwiamesh.app.NwiaEndpoint
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class NwiaBridge(private val context: Context, engine: FlutterEngine) {
    private var endpoint: NwiaEndpoint? = null
    private var sink: EventChannel.EventSink? = null
    private val methods = MethodChannel(engine.dartExecutor.binaryMessenger, "com.nwiamesh/endpoint")
    private val events = EventChannel(engine.dartExecutor.binaryMessenger, "com.nwiamesh/events")
    init {
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink) { sink = eventSink }
            override fun onCancel(arguments: Any?) { sink = null }
        })
        methods.setMethodCallHandler { call, result ->
            try {
                if (call.method == "open") {
                    if (endpoint == null) endpoint = NwiaEndpoint(context.applicationContext) { event -> sink?.success(event) }
                    result.success(endpoint!!.profile())
                } else if (call.method == "close") { endpoint?.close(); endpoint = null; result.success(null) }
                else {
                    val local = endpoint ?: throw IllegalStateException("NWIA endpoint is not open")
                    when (call.method) {
                        "frame" -> { local.handle(call.arguments as ByteArray); result.success(null) }
                        "sendDm" -> { val a = call.arguments as Map<*, *>; result.success(local.sendDm(a["peer"] as String, a["text"] as String, (a["timestamp"] as Number).toLong())) }
                        "profile" -> result.success(local.profile())
                        "configure" -> { @Suppress("UNCHECKED_CAST") val a = call.arguments as Map<String, Any>; local.configure(a); result.success(null) }
                        "pushToken" -> {
                            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                                .addOnSuccessListener { token -> result.success(token) }
                                .addOnFailureListener { result.error("NWIA_PUSH", "Token unavailable", null) }
                        }
                        "mqttConnect" -> { local.connectMqtt(); result.success(null) }
                        "mqttDisconnect" -> { local.disconnectMqtt(); result.success(null) }
                        "scan" -> local.scan { devices -> result.success(devices) }
                        "bleConnect" -> { local.connectBle(call.arguments as String); result.success(null) }
                        "bleDisconnect" -> { local.disconnectBle(); result.success(null) }
                        "secureRead" -> result.success(local.secureRead(call.arguments as String))
                        "secureWrite" -> { val a = call.arguments as Map<*, *>; local.secureWrite(a["scope"] as String, a["value"] as String); result.success(null) }
                        else -> result.notImplemented()
                    }
                }
            } catch (error: Exception) {
                // Do not echo arguments, secrets, payloads or exception messages into logs.
                result.error("NWIA_ENDPOINT", error.javaClass.simpleName, null)
            }
        }
    }
    fun dispose() { endpoint?.close(); endpoint = null; sink = null; methods.setMethodCallHandler(null); events.setStreamHandler(null) }
}
