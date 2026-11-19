import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshcore_open/nwia/nwia_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.nwiamesh/endpoint');
  final calls = <MethodCall>[];
  setUp(() {
    calls.clear();
    NwiaBridge.activeScope = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'open') {
            return {'publicKey': 'AABBCCDDEE${'00' * 27}'};
          }
          if (call.method == 'sendDm') {
            return {'id': 'ciphertext-id', 'ackHash': 42};
          }
          return null;
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    NwiaBridge.activeScope = null;
  });

  test(
    'phone identity scopes encrypted channel storage and closes cleanly',
    () async {
      final bridge = NwiaBridge();
      await bridge.open();
      expect(NwiaBridge.activeScope, 'aabbccddee');
      await NwiaBridge.writeChannels('AABBCCDDEE', 'encrypted-vault-input');
      expect(calls.last.method, 'secureWrite');
      expect((calls.last.arguments as Map)['scope'], 'aabbccddee');
      await bridge.close();
      expect(NwiaBridge.activeScope, isNull);
    },
  );

  test(
    'direct messages use the phone crypto endpoint with full recipient key',
    () async {
      final bridge = NwiaBridge();
      final peer = 'ab' * 32;
      final result = await bridge.sendDm(peer, 'test message', 123);
      expect(result['id'], 'ciphertext-id');
      expect(calls.single.method, 'sendDm');
      expect(calls.single.arguments, {
        'peer': peer,
        'text': 'test message',
        'timestamp': 123,
      });
    },
  );

  test('legacy frames terminate at the local endpoint', () async {
    await NwiaBridge().frame(Uint8List.fromList([32, 0, 1, 2]));
    expect(calls.single.method, 'frame');
    expect(calls.single.arguments, [32, 0, 1, 2]);
  });
}
