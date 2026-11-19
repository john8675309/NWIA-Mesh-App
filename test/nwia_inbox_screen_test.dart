import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:meshcore_open/connector/meshcore_connector.dart';
import 'package:meshcore_open/l10n/app_localizations.dart';
import 'package:meshcore_open/nwia/nwia_bridge.dart';
import 'package:meshcore_open/screens/nwia_screen.dart';
import 'package:meshcore_open/theme/mesh_theme.dart';

class PreviewBridge extends NwiaBridge {
  Map<String, dynamic>? saved;
  bool synced = false;
  @override
  Future<Map<String, dynamic>> profile() async => {
    'name': 'Example phone',
    'host': 'broker.example.invalid',
    'port': 8883,
    'group': 'example',
    'user': 'phone-example',
    'origin': '020000000001',
    'publicKey': 'ab' * 32,
    'relayUrl': 'https://broker.example.invalid',
    'pushEnabled': true,
    'inboxReady': true,
    'inboxPending': 2,
    'inboxDropped': 0,
  };
  @override
  Future<void> configure(Map<String, dynamic> values) async {
    saved = values;
  }

  @override
  Future<void> inboxSync() async {
    synced = true;
  }

  @override
  Future<void> close() async {}
}

class PreviewConnector extends MeshCoreConnector {
  final bridge = PreviewBridge();
  @override
  NwiaBridge get nwia => bridge;
  @override
  bool get isNwia => true;
  @override
  bool get isConnected => true;
}

void main() {
  testWidgets('inbox controls show protected setup and save explicit opt-out', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(440, 1220);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final font = FontLoader('Inter')
      ..addFont(rootBundle.load('assets/fonts/Inter.ttf'));
    await font.load();
    final connector = PreviewConnector()
      ..nwiaRadioReady = true
      ..nwiaMqttReady = true;
    await tester.pumpWidget(
      ChangeNotifierProvider<MeshCoreConnector>.value(
        value: connector,
        child: MaterialApp(
          locale: const Locale('en'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: MeshTheme.light(),
          home: const RepaintBoundary(key: Key('preview'), child: NwiaScreen()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Inbox registered'), findsOneWidget);
    expect(
      find.text('Encrypted packets awaiting chat import: 2'),
      findsOneWidget,
    );
    await expectLater(
      find.byKey(const Key('preview')),
      matchesGoldenFile('goldens/nwia_inbox.png'),
    );
    await tester.tap(find.byType(SwitchListTile));
    await tester.ensureVisible(find.text('Save and sync inbox'));
    await tester.tap(find.text('Save and sync inbox'));
    await tester.pumpAndSettle();
    expect(connector.bridge.saved?['pushEnabled'], false);
    expect(
      connector.bridge.saved?['relayUrl'],
      'https://broker.example.invalid',
    );
    expect(connector.bridge.synced, true);
    await tester.pumpWidget(const SizedBox());
    connector.dispose();
  });
}
