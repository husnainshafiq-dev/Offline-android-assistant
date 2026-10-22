import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:offline_voice_assistant/main.dart';

void main() {
  testWidgets('App renders correctly', (WidgetTester tester) async {
    await tester.pumpWidget(const VoiceAssistantApp());

    // Verify the app title and key UI elements are present
    expect(find.text('Voice Assistant'), findsOneWidget);
    expect(find.text('Enable Assistant'), findsOneWidget);
  });
}
