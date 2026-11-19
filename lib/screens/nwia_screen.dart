import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:provider/provider.dart';

import '../connector/meshcore_connector.dart';
import '../l10n/l10n.dart';
import '../utils/main_navigation.dart';

class NwiaScreen extends StatefulWidget {
  const NwiaScreen({super.key});
  @override
  State<NwiaScreen> createState() => _NwiaScreenState();
}

class _NwiaScreenState extends State<NwiaScreen> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _host = TextEditingController();
  final _port = TextEditingController(text: '8883');
  final _group = TextEditingController();
  final _user = TextEditingController();
  final _password = TextEditingController();
  final _origin = TextEditingController();
  bool _busy = true;
  String? _error;
  String _publicId = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _open());
  }

  Future<void> _open() async {
    await _run(() async {
      final connector = context.read<MeshCoreConnector>();
      if (!connector.isNwia || !connector.isConnected) {
        await connector.connectNwia();
      }
      final profile = await connector.nwia.profile();
      _name.text = profile['name'] as String? ?? '';
      _host.text = profile['host'] as String? ?? '';
      _port.text = '${profile['port'] ?? 8883}';
      _group.text = profile['group'] as String? ?? '';
      _user.text = profile['user'] as String? ?? '';
      _origin.text = profile['origin'] as String? ?? '';
      _publicId = profile['publicKey'] as String;
    });
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } catch (_) {
      if (mounted) _error = context.l10n.nwia_operationFailed;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _save() async {
    if (!_form.currentState!.validate()) {
      throw const FormatException();
    }
    await context.read<MeshCoreConnector>().nwia.configure({
      'name': _name.text.trim(),
      'host': _host.text.trim(),
      'port': int.parse(_port.text),
      'group': _group.text.trim(),
      'user': _user.text,
      'password': _password.text,
      'origin': _origin.text.trim().toLowerCase(),
    });
    _password.clear();
  }

  Future<void> _scan() async {
    final connector = context.read<MeshCoreConnector>();
    // The plugin handles Android's runtime Nearby Devices permission prompt.
    await FlutterBluePlus.startScan(timeout: const Duration(seconds: 1));
    await FlutterBluePlus.stopScan();
    final devices = await connector.nwia.scan();
    if (!mounted) return;
    final selected = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(context.l10n.nwia_chooseCompanion),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView(
            shrinkWrap: true,
            children: [
              for (final device in devices)
                ListTile(
                  title: Text(device['name'] as String),
                  subtitle: Text(device['id'] as String),
                  onTap: () => Navigator.pop(context, device['id'] as String),
                ),
              if (devices.isEmpty) Text(context.l10n.nwia_noCompanion),
            ],
          ),
        ),
      ),
    );
    if (selected != null) await connector.nwia.bleConnect(selected);
  }

  Widget _field(
    TextEditingController controller,
    String label, {
    bool secret = false,
    String? Function(String?)? validator,
  }) => TextFormField(
    controller: controller,
    obscureText: secret,
    enableSuggestions: !secret,
    autocorrect: false,
    decoration: InputDecoration(labelText: label),
    validator: validator,
  );

  @override
  Widget build(BuildContext context) {
    final connector = context.watch<MeshCoreConnector>();
    final l10n = context.l10n;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.nwia_title), centerTitle: true),
      body: SafeArea(
        child: Form(
          key: _form,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(
                l10n.nwia_keyOwnership,
                style: Theme.of(context).textTheme.bodyLarge,
              ),
              const SizedBox(height: 16),
              if (_publicId.isNotEmpty)
                SelectableText('${l10n.nwia_publicId}\n$_publicId'),
              const SizedBox(height: 16),
              Text(
                connector.nwiaRadioReady
                    ? l10n.nwia_radioReady
                    : l10n.nwia_radioUnavailable,
              ),
              Text(
                connector.nwiaMqttReady
                    ? l10n.nwia_mqttReady
                    : l10n.nwia_mqttUnavailable,
              ),
              if (_busy) const LinearProgressIndicator(),
              if (_error != null)
                Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              Wrap(
                spacing: 8,
                children: [
                  FilledButton(
                    onPressed: _busy
                        ? null
                        : () => _run(() async {
                            if (connector.nwiaRadioReady) {
                              await connector.nwia.bleDisconnect();
                            } else {
                              await _scan();
                            }
                          }),
                    child: Text(
                      connector.nwiaRadioReady
                          ? l10n.nwia_disconnectCompanion
                          : l10n.nwia_findCompanion,
                    ),
                  ),
                  OutlinedButton(
                    onPressed: _busy
                        ? null
                        : () => _run(() async {
                            if (connector.nwiaMqttReady) {
                              await connector.nwia.mqttDisconnect();
                            } else {
                              await _save();
                              await connector.nwia.mqttConnect();
                            }
                          }),
                    child: Text(
                      connector.nwiaMqttReady
                          ? l10n.nwia_disconnectMqtt
                          : l10n.nwia_connectMqtt,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              _field(
                _name,
                l10n.nwia_name,
                validator: (v) => v == null || v.trim().isEmpty || v.length > 24
                    ? l10n.nwia_invalidValue
                    : null,
              ),
              _field(_host, l10n.nwia_host),
              _field(
                _port,
                l10n.nwia_port,
                validator: (v) {
                  final n = int.tryParse(v ?? '');
                  return n == null || n < 1 || n > 65535
                      ? l10n.nwia_invalidValue
                      : null;
                },
              ),
              _field(_group, l10n.nwia_group),
              _field(_user, l10n.nwia_user),
              _field(_password, l10n.nwia_password, secret: true),
              _field(_origin, l10n.nwia_origin),
              const SizedBox(height: 16),
              OutlinedButton(
                onPressed: _busy ? null : () => _run(_save),
                child: Text(l10n.nwia_save),
              ),
              FilledButton(
                onPressed: _busy || !connector.isNwia || !connector.isConnected
                    ? null
                    : () => navigateToMainAfterConnect(context),
                child: Text(l10n.nwia_openChats),
              ),
              const SizedBox(height: 16),
              Text(l10n.nwia_limits),
            ],
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    for (final c in [_name, _host, _port, _group, _user, _password, _origin]) {
      c.dispose();
    }
    super.dispose();
  }
}
