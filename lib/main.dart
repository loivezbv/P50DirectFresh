import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:image/image.dart' as img;
import 'package:print_master_ble/print_master_ble.dart';

void main() {
  FlutterBluePlus.setLogLevel(LogLevel.none);
  runApp(const P50App());
}

class P50App extends StatelessWidget {
  const P50App({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'P50 BLE TEST',
    theme: ThemeData(useMaterial3: true),
    home: const P50Home(),
  );
}

class FbpPrinterDevice implements PrinterDevice {
  final BluetoothDevice device;
  BluetoothCharacteristic? writeChar;
  BluetoothCharacteristic? flowChar;

  FbpPrinterDevice(this.device);

  Future<void> initialize() async {
    final services = await device.discoverServices();
    for (final s in services) {
      if (!PrinterBleProtocol.isServiceUuid(s.uuid.toString())) continue;
      for (final c in s.characteristics) {
        final u = c.uuid.toString();
        if (PrinterBleProtocol.isWriteCharacteristic(u)) writeChar = c;
        if (PrinterBleProtocol.isFlowControlCharacteristic(u)) {
          flowChar = c;
          try { await c.setNotifyValue(true); } catch (_) {}
        }
      }
    }
    if (writeChar == null) {
      throw Exception('P50 쓰기 채널(FF02)을 찾지 못함');
    }
  }

  @override
  String get deviceName => device.platformName.isEmpty ? 'P50S' : device.platformName;

  @override
  bool get isConnected => device.isConnected;

  @override
  BleWriteCallback get write => (data, withoutResponse) async {
    await writeChar!.write(data, withoutResponse: withoutResponse);
    return true;
  };

  @override
  BleNotifyCallback? get notify {
    if (flowChar == null) return null;
    return () => flowChar!.lastValueStream;
  }

  @override
  Future<void> disconnect() => device.disconnect();
}

class P50Home extends StatefulWidget {
  const P50Home({super.key});
  @override
  State<P50Home> createState() => _P50HomeState();
}

class _P50HomeState extends State<P50Home> {
  String status = 'P50S 검색 준비';
  final Map<String, BluetoothDevice> devices = {};
  String? selected;
  FbpPrinterDevice? printer;
  PrintMasterHandler? handler;
  StreamSubscription<List<ScanResult>>? scanSub;

  Future<void> scan() async {
    setState(() => status = 'BLE P50S 검색 중...');
    devices.clear();
    selected = null;
    await scanSub?.cancel();
    scanSub = FlutterBluePlus.scanResults.listen((results) {
      var changed = false;
      for (final r in results) {
        final n = r.device.platformName.isNotEmpty
            ? r.device.platformName
            : r.advertisementData.advName;
        if (n.toUpperCase().contains('P50')) {
          devices[r.device.remoteId.str] = r.device;
          changed = true;
        }
      }
      if (changed && mounted) {
        setState(() {
          selected ??= devices.keys.first;
          status = 'P50S 발견';
        });
      }
    });
    try {
      await FlutterBluePlus.startScan(timeout: const Duration(seconds: 6));
      await Future.delayed(const Duration(seconds: 6));
      await FlutterBluePlus.stopScan();
      if (mounted && devices.isEmpty) setState(() => status = 'P50S를 못 찾음 - 새로고침');
    } catch (e) {
      if (mounted) setState(() => status = '검색 실패: $e');
    }
  }

  Future<void> connect() async {
    final id = selected;
    if (id == null) return;
    try {
      setState(() => status = 'P50S BLE 연결 중...');
      await FlutterBluePlus.stopScan();
      final d = devices[id]!;
      await d.connect(timeout: const Duration(seconds: 15));
      final p = FbpPrinterDevice(d);
      await p.initialize();
      handler?.dispose();
      printer = p;
      handler = PrintMasterHandler(p);
      setState(() => status = 'P50S BLE 연결 성공 (FF02)');
    } catch (e) {
      setState(() => status = '연결 실패: $e');
    }
  }

  img.Image makeTestImage() {
    final im = img.Image(width: 384, height: 240);
    img.fill(im, color: img.ColorRgb8(255, 255, 255));
    for (var y = 20; y < 220; y++) {
      for (var x = 20; x < 364; x++) {
        final border = x < 28 || x >= 356 || y < 28 || y >= 212;
        final bars = y >= 70 && y < 170 && ((x ~/ 16) % 2 == 0);
        if (border || bars) im.setPixelRgba(x, y, 0, 0, 0, 255);
      }
    }
    return im;
  }

  Future<void> printTest() async {
    final h = handler;
    if (h == null) {
      setState(() => status = '먼저 P50S BLE 연결');
      return;
    }
    try {
      setState(() => status = 'P50 전용 0x1F 출력 전송 중...');
      await h.printImage(makeTestImage(), printerWidth: 384, alignment: PrintAlignment.center);
      setState(() => status = 'P50 전용 출력 명령 완료');
    } catch (e) {
      setState(() => status = '출력 실패: $e');
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => scan());
  }

  @override
  void dispose() {
    scanSub?.cancel();
    handler?.dispose();
    printer?.disconnect();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('P50S VERIFIED DIRECT TEST')),
    body: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(status),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: selected,
          decoration: const InputDecoration(border: OutlineInputBorder(), labelText: '검색된 P50 프린터'),
          items: devices.entries.map((e) => DropdownMenuItem(
            value: e.key,
            child: Text('${e.value.platformName}  ${e.key}'),
          )).toList(),
          onChanged: (v) => setState(() => selected = v),
        ),
        const SizedBox(height: 12),
        FilledButton(onPressed: scan, child: const Text('BLE P50 새로고침')),
        FilledButton(onPressed: selected == null ? null : connect, child: const Text('P50 BLE 연결')),
        const SizedBox(height: 8),
        FilledButton.tonal(onPressed: printTest, child: const Text('P50S VERIFIED TEST 출력')),
      ]),
    ),
  );
}
