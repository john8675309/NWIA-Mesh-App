import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:meshcore_open/models/channel.dart';
import 'package:meshcore_open/storage/channel_store.dart';
import 'package:meshcore_open/storage/prefs_manager.dart';
import 'package:meshcore_open/nwia/nwia_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const endpoint = MethodChannel('com.nwiamesh/endpoint');
  setUp(() async {
    PrefsManager.reset();
    SharedPreferences.setMockInitialValues({
      'nwia_identity_scope': 'aabbccddee',
    });
    await PrefsManager.initialize();
    NwiaBridge.activeScope = null;
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(endpoint, null);
    PrefsManager.reset();
  });
  test(
    'closed NWIA endpoint cannot spill channel keys into preferences',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(endpoint, (_) async {
            throw PlatformException(code: 'NWIA_ENDPOINT');
          });
      final store = ChannelStore()..setPublicKeyHex = 'aabbccddee';
      await expectLater(
        store.saveChannels([
          Channel(
            index: 0,
            name: 'test',
            psk: Uint8List.fromList(List.filled(16, 7)),
          ),
        ]),
        throwsA(isA<PlatformException>()),
      );
      expect(PrefsManager.instance.getString(store.keyFor), isNull);
    },
  );
  test(
    'NWIA load does not fall back to legacy plaintext preferences',
    () async {
      await PrefsManager.instance.setString(
        'channelsaabbccddee',
        '[{"psk":"legacy"}]',
      );
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(endpoint, (_) async => null);
      final store = ChannelStore()..setPublicKeyHex = 'aabbccddee';
      expect(await store.loadChannels(), isEmpty);
    },
  );
}
