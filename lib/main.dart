import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const P50App());

class P50App extends StatelessWidget {
  const P50App({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'P50 DIRECT TEST',
    theme: ThemeData(useMaterial3: true),
    home: const P50Home(),
  );
}

class P50Home extends StatefulWidget {
  const P50Home({super.key});
  @override
  State<P50Home> createState() => _P50HomeState();
}

class _P50HomeState extends State<P50Home> {
  static const bt = MethodChannel('ddoolive/p50_bt');
  List<Map<String, String>> devices = [];
  String status = '준비';
  String? selected;

  Future<void> load() async {
    try {
      final r = await bt.invokeMethod<List<dynamic>>('pairedDevices') ?? [];
      setState(() {
        devices = r.map((e) => Map<String, String>.from(e as Map)).toList();
        status = '페어링된 기기 ${devices.length}개';
      });
    } on PlatformException catch (e) {
      setState(() => status = e.message ?? e.code);
    }
  }

  Future<void> connect() async {
    if (selected == null) return;
    try {
      await bt.invokeMethod('connect', {'address': selected});
      setState(() => status = 'P50 연결 성공');
    } on PlatformException catch (e) {
      setState(() => status = '연결 실패: ${e.message ?? e.code}');
    }
  }

  Future<void> printTest() async {
    try {
      await bt.invokeMethod('printTest');
      setState(() => status = '테스트 데이터 전송 완료');
    } on PlatformException catch (e) {
      setState(() => status = '출력 실패: ${e.message ?? e.code}');
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => load());
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('P50 DIRECT TEST')),
    body: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(status),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          initialValue: selected,
          decoration: const InputDecoration(border: OutlineInputBorder(), labelText: '페어링된 프린터'),
          items: devices.map((d) => DropdownMenuItem(
            value: d['address'],
            child: Text('${d['name'] ?? '기기'}  ${d['address']}'),
          )).toList(),
          onChanged: (v) => setState(() => selected = v),
        ),
        const SizedBox(height: 12),
        FilledButton(onPressed: load, child: const Text('기기 새로고침')),
        FilledButton(onPressed: selected == null ? null : connect, child: const Text('P50 연결')),
        const SizedBox(height: 8),
        FilledButton.tonal(onPressed: printTest, child: const Text('DIRECT TEST 출력')),
      ]),
    ),
  );
}
