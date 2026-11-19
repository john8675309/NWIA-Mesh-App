import 'package:flutter/services.dart';

/// Android phone-local endpoint. These frames are never companion BLE writes.
class NwiaBridge {
  static const _methods = MethodChannel('com.nwiamesh/endpoint');
  static const _events = EventChannel('com.nwiamesh/events');
  static String? activeScope;

  Stream<Map<String, dynamic>> get events => _events
      .receiveBroadcastStream()
      .map((event) => Map<String, dynamic>.from(event as Map));

  Future<Map<String, dynamic>> open() async {
    final value = await _methods.invokeMapMethod<String, dynamic>('open');
    final profile = value!;
    activeScope = (profile['publicKey'] as String)
        .substring(0, 10)
        .toLowerCase();
    return profile;
  }

  Future<void> close() async {
    activeScope = null;
    await _methods.invokeMethod<void>('close');
  }

  Future<void> frame(Uint8List frame) => _methods.invokeMethod('frame', frame);
  Future<Map<String, dynamic>> sendDm(
    String peer,
    String text,
    int timestamp,
  ) async => (await _methods.invokeMapMethod<String, dynamic>('sendDm', {
    'peer': peer,
    'text': text,
    'timestamp': timestamp,
  }))!;
  Future<Map<String, dynamic>> profile() async =>
      (await _methods.invokeMapMethod<String, dynamic>('profile'))!;
  Future<void> configure(Map<String, dynamic> values) =>
      _methods.invokeMethod('configure', values);
  Future<List<Map<String, dynamic>>> inboxFrames() async =>
      (await _methods.invokeListMethod<dynamic>(
        'inboxFrames',
      ))!.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  Future<void> inboxCommit(String id) =>
      _methods.invokeMethod('inboxCommit', id);
  Future<void> inboxSync() => _methods.invokeMethod('inboxSync');
  Future<String?> pushToken() => _methods.invokeMethod<String>('pushToken');
  Future<void> mqttConnect() => _methods.invokeMethod('mqttConnect');
  Future<void> mqttDisconnect() => _methods.invokeMethod('mqttDisconnect');
  Future<List<Map<String, dynamic>>> scan() async =>
      (await _methods.invokeListMethod<dynamic>(
        'scan',
      ))!.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  Future<void> bleConnect(String address) =>
      _methods.invokeMethod('bleConnect', address);
  Future<void> bleDisconnect() => _methods.invokeMethod('bleDisconnect');
  static Future<String?> readChannels(String scope) =>
      _methods.invokeMethod('secureRead', scope.toLowerCase());
  static Future<void> writeChannels(String scope, String value) =>
      _methods.invokeMethod('secureWrite', {
        'scope': scope.toLowerCase(),
        'value': value,
      });
}
