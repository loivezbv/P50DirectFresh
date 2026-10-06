import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const P50App());

class P50App extends StatelessWidget {
  const P50App({super.key});
  @override
  Widget build(BuildContext context) => const MaterialApp(debugShowCheckedModeBanner: false, home: P50Home());
}

class P50Home extends StatefulWidget {
  const P50Home({super.key});
  @override
  State<P50Home> createState() => _P50HomeState();
}

class _P50HomeState extends State<P50Home> {
  static const channel = MethodChannel('ddoolive/p50_bt');
  List<Map<String, String>> devices = [];
  String? selected;
  String status = 'P50S 검색 준비.';

  Future<void> refresh() async {
    try {
      final raw = await channel.invokeMethod<List<dynamic>>('pairedDevices') ?? [];
      final list = raw.map((e) => Map<String, String>.from(e as Map)).toList();
      setState(() {
        devices = list;
        selected = list.isEmpty ? null : list.first['address'];
        status = list.isEmpty ? '페어링된 P50S 없음' : 'P50S 선택 준비';
      });
    } catch (e) { setState(() => status = '검색 실패: $e'); }
  }

  Future<void> connect() async {
    if (selected == null) return;
    try {
      setState(() => status = '연결 중...');
      await channel.invokeMethod('connect', {'address': selected});
      setState(() => status = 'P50S 연결 성공');
    } catch (e) { setState(() => status = '연결 실패: $e'); }
  }

  Future<void> printTest() async {
    try {
      await channel.invokeMethod('printTest');
      setState(() => status = '테스트 데이터 전송 완료');
    } catch (e) { setState(() => status = '전송 실패: $e'); }
  }

  @override
  void initState() { super.initState(); WidgetsBinding.instance.addPostFrameCallback((_) => refresh()); }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('P50 DIRECT TEST')),
    body: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(status), const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          value: selected,
          decoration: const InputDecoration(border: OutlineInputBorder(), labelText: '페어링된 프린터'),
          items: devices.map((d) => DropdownMenuItem(value: d['address'], child: Text("${d['name']}  ${d['address']}"))).toList(),
          onChanged: (v) => setState(() => selected = v),
        ),
        const SizedBox(height: 12),
        FilledButton(onPressed: refresh, child: const Text('새로고침')),
        FilledButton(onPressed: selected == null ? null : connect, child: const Text('P50S 연결')),
        const SizedBox(height: 8),
        FilledButton.tonal(onPressed: printTest, child: const Text('TEST 출력')),
      ]),
    ),
  );
}