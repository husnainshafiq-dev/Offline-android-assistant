import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
    ),
  );
  runApp(const VoiceAssistantApp());
}

class VoiceAssistantApp extends StatelessWidget {
  const VoiceAssistantApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Offline Voice Assistant',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF6C63FF),
        fontFamily: 'Roboto',
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with TickerProviderStateMixin {
  static const _methodChannel =
      MethodChannel('com.offlineassistant/voice_assistant');
  static const _eventChannel =
      EventChannel('com.offlineassistant/voice_status');

  bool _isServiceRunning = false;
  bool _permissionsGranted = false;
  bool _batteryOptimized = true;
  bool _modelReady = false;
  bool _isDownloading = false;
  int _downloadProgress = 0;
  String _statusMessage = 'Idle';
  String _lastRecognizedText = '';
  String _lastAction = '';
  List<Map<String, dynamic>> _notes = [];
  bool _isLoading = false;

  // Porcupine key management
  String _porcupineKey = '';
  bool _porcupineKeySet = false;
  final TextEditingController _porcupineKeyController = TextEditingController();
  bool _isKeyVisible = true;

  late AnimationController _pulseController;
  late AnimationController _waveController;
  late Animation<double> _pulseAnimation;

  StreamSubscription? _statusSubscription;

  @override
  void initState() {
    super.initState();

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    );
    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.3).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    _waveController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 2),
    );

    _initializeApp();
  }

  Future<void> _initializeApp() async {
    await _checkPermissions();
    await _checkBatteryOptimization();
    await _checkModelReady();
    await _loadPorcupineKey();
    await _checkServiceStatus();
    await _loadNotes();
    _listenToStatusEvents();
  }

  Future<void> _loadPorcupineKey() async {
    try {
      final key = await _methodChannel.invokeMethod('getPorcupineKey');
      final keyStr = key as String? ?? '';
      setState(() {
        _porcupineKey = keyStr;
        _porcupineKeySet = keyStr.isNotEmpty;
        if (keyStr.isNotEmpty) {
          _porcupineKeyController.text = keyStr;
        }
      });
    } catch (e) {
      debugPrint('Error loading Porcupine key: $e');
    }
  }

  Future<void> _savePorcupineKey() async {
    // Clean key: remove all whitespace, newlines, and zero-width spaces
    final key = _porcupineKeyController.text
        .replaceAll(RegExp('\\s+'), '')
        .replaceAll('\u200B', '')
        .trim();
    if (key.isEmpty) {
      _showSnackBar('Please enter a valid Porcupine access key');
      return;
    }

    // Save the cleaned key immediately (validation is optional)
    try {
      await _methodChannel.invokeMethod('setPorcupineKey', {'key': key});
      setState(() {
        _porcupineKey = key;
        _porcupineKeySet = true;
        _porcupineKeyController.text = key; // Update field with cleaned key
      });
      _showSnackBar('✅ Porcupine access key saved!');
    } catch (e) {
      debugPrint('Error saving Porcupine key: $e');
      _showSnackBar('Error saving key: $e');
    }
  }

  Future<void> _validateAndSaveKey() async {
    final key = _porcupineKeyController.text
        .replaceAll(RegExp('\\s+'), '')
        .replaceAll('\u200B', '')
        .trim();
    if (key.isEmpty) {
      _showSnackBar('Please enter a valid Porcupine access key');
      return;
    }

    setState(() => _isLoading = true);
    _showSnackBar('⏳ Validating key with Porcupine...');

    try {
      final result = await _methodChannel
          .invokeMethod('validatePorcupineKey', {'key': key});
      final map = Map<String, dynamic>.from(result as Map);
      final isValid = map['valid'] as bool? ?? false;
      final error = map['error'] as String? ?? '';

      if (isValid) {
        // Key is valid — save it
        await _methodChannel.invokeMethod('setPorcupineKey', {'key': key});
        setState(() {
          _porcupineKey = key;
          _porcupineKeySet = true;
          _porcupineKeyController.text = key;
        });
        _showSnackBar('✅ Key validated and saved successfully!');
      } else {
        _showSnackBar('❌ Invalid key: $error');
      }
    } catch (e) {
      debugPrint('Error validating Porcupine key: $e');
      // Fall back to just saving without validation
      await _savePorcupineKey();
    } finally {
      setState(() => _isLoading = false);
    }
  }

  Future<void> _clearPorcupineKey() async {
    try {
      await _methodChannel.invokeMethod('setPorcupineKey', {'key': ''});
      setState(() {
        _porcupineKey = '';
        _porcupineKeySet = false;
        _porcupineKeyController.clear();
      });
      _showSnackBar('Porcupine key removed');
    } catch (e) {
      debugPrint('Error clearing Porcupine key: $e');
    }
  }

  Future<void> _checkPermissions() async {
    try {
      final result = await _methodChannel.invokeMethod('checkPermissions');
      setState(() => _permissionsGranted = result as bool);
    } catch (e) {
      debugPrint('Error checking permissions: $e');
    }
  }

  Future<void> _requestPermissions() async {
    try {
      await _methodChannel.invokeMethod('requestPermissions');
      await Future.delayed(const Duration(seconds: 1));
      await _checkPermissions();
    } catch (e) {
      debugPrint('Error requesting permissions: $e');
    }
  }

  Future<void> _checkBatteryOptimization() async {
    try {
      final result = await _methodChannel.invokeMethod('isBatteryOptimized');
      setState(() => _batteryOptimized = result as bool);
    } catch (e) {
      debugPrint('Error checking battery optimization: $e');
    }
  }

  Future<void> _requestBatteryOptimization() async {
    try {
      await _methodChannel.invokeMethod('requestBatteryOptimization');
      await Future.delayed(const Duration(seconds: 2));
      await _checkBatteryOptimization();
    } catch (e) {
      debugPrint('Error requesting battery optimization: $e');
    }
  }

  Future<void> _checkModelReady() async {
    try {
      final result = await _methodChannel.invokeMethod('isModelReady');
      setState(() => _modelReady = result as bool);
    } catch (e) {
      debugPrint('Error checking model: $e');
    }
  }

  Future<void> _downloadModel() async {
    setState(() {
      _isDownloading = true;
      _downloadProgress = 0;
    });
    try {
      await _methodChannel.invokeMethod('downloadModel');
    } catch (e) {
      debugPrint('Error downloading model: $e');
      setState(() => _isDownloading = false);
    }
  }

  Future<void> _checkServiceStatus() async {
    try {
      final result = await _methodChannel.invokeMethod('getServiceStatus');
      final map = Map<String, dynamic>.from(result as Map);
      setState(() {
        _isServiceRunning = map['isRunning'] as bool;
        _lastRecognizedText = map['lastText'] as String? ?? '';
        _lastAction = map['lastAction'] as String? ?? '';
        _statusMessage = map['status'] as String? ?? 'Idle';
      });

      if (_isServiceRunning) {
        _pulseController.repeat(reverse: true);
        _waveController.repeat();
      }
    } catch (e) {
      debugPrint('Error checking service status: $e');
    }
  }

  void _listenToStatusEvents() {
    _statusSubscription =
        _eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        final map = Map<String, dynamic>.from(event);
        final status = map['status'] as String? ?? '';

        setState(() {
          if (status == 'downloading') {
            _isDownloading = true;
            _downloadProgress =
                int.tryParse(map['text'] as String? ?? '0') ?? 0;
            _lastAction = map['action'] as String? ?? '';
          } else if (status == 'modelReady') {
            _isDownloading = false;
            _modelReady = true;
            _downloadProgress = 100;
            _lastAction = map['action'] as String? ?? '';
          } else if (status == 'modelError') {
            _isDownloading = false;
            _lastAction = map['action'] as String? ?? '';
            _showSnackBar(map['action'] as String? ?? 'Download failed');
          } else if (status == 'permissionResult') {
            _permissionsGranted = map['text'] == 'granted';
          } else if (status == 'noteSaved') {
            _lastAction = map['action'] as String? ?? '';
            _statusMessage = 'Executed';
            // Auto-refresh the notes list
            _loadNotes();
          } else if (status == 'showNotes') {
            _lastAction = map['action'] as String? ?? '';
            // Navigate to the full notes page
            _loadNotes().then((_) {
              if (mounted) {
                Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => NotesPage(notes: _notes, onRefresh: _loadNotes),
                  ),
                );
              }
            });
          } else {
            _statusMessage = status;
            final text = map['text'] as String? ?? '';
            final action = map['action'] as String? ?? '';
            if (text.isNotEmpty) _lastRecognizedText = text;
            if (action.isNotEmpty) _lastAction = action;
          }
        });
      }
    }, onError: (error) {
      debugPrint('Event channel error: $error');
    });
  }

  Future<void> _toggleService() async {
    setState(() => _isLoading = true);

    try {
      if (_isServiceRunning) {
        await _methodChannel.invokeMethod('stopService');
        _pulseController.stop();
        _pulseController.reset();
        _waveController.stop();
        _waveController.reset();
        setState(() {
          _isServiceRunning = false;
          _statusMessage = 'Stopped';
        });
      } else {
        // Auto-save key if there's text in the field that isn't saved yet
        final currentKey = _porcupineKeyController.text
            .replaceAll(RegExp('\\s+'), '')
            .replaceAll('\u200B', '')
            .trim();
        if (currentKey.isNotEmpty && currentKey != _porcupineKey) {
          await _savePorcupineKey();
        }

        if (_porcupineKey.isEmpty) {
          _showSnackBar('Please enter and save a valid Porcupine key first.');
          setState(() => _isLoading = false);
          return;
        }

        final result = await _methodChannel.invokeMethod('startService');
        if (result == true) {
          _pulseController.repeat(reverse: true);
          _waveController.repeat();
          setState(() {
            _isServiceRunning = true;
            _statusMessage = 'Listening';
          });
        } else {
          _showSnackBar('Failed to start. Check permissions & model.');
        }
      }
    } catch (e) {
      debugPrint('Error toggling service: $e');
      _showSnackBar('Error: $e');
    } finally {
      setState(() => _isLoading = false);
    }
  }

  Future<void> _loadNotes() async {
    try {
      final result = await _methodChannel.invokeMethod('getNotes');
      if (result is List) {
        setState(() {
          _notes = result
              .map((e) => Map<String, dynamic>.from(e as Map))
              .toList();
        });
      }
    } catch (e) {
      debugPrint('Error loading notes: $e');
    }
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        backgroundColor: const Color(0xFF2D2D44),
      ),
    );
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _waveController.dispose();
    _porcupineKeyController.dispose();
    _statusSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFF0D0D1A),
              Color(0xFF1A1A2E),
              Color(0xFF16213E),
              Color(0xFF0F3460),
            ],
            stops: [0.0, 0.3, 0.6, 1.0],
          ),
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
            child: Column(
              children: [
                _buildHeader(),
                const SizedBox(height: 32),
                _buildMicrophoneOrb(),
                const SizedBox(height: 32),
                if (!_permissionsGranted) ...[
                  _buildPermissionCard(),
                  const SizedBox(height: 16),
                ],
                if (!_modelReady) ...[
                  _buildModelCard(),
                  const SizedBox(height: 16),
                ],
                _buildPorcupineKeyCard(),
                const SizedBox(height: 16),
                if (_batteryOptimized) ...[
                  _buildBatteryCard(),
                  const SizedBox(height: 16),
                ],
                _buildStatusCard(),
                const SizedBox(height: 16),
                _buildCommandsCard(),
                const SizedBox(height: 16),
                _buildNotesCard(),
                const SizedBox(height: 32),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return Row(
      children: [
        Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              colors: [Color(0xFF6C63FF), Color(0xFF3F51B5)],
            ),
            borderRadius: BorderRadius.circular(14),
            boxShadow: [
              BoxShadow(
                color: const Color(0xFF6C63FF).withValues(alpha: 0.3),
                blurRadius: 12,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: const Icon(Icons.mic, color: Colors.white, size: 24),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Voice Assistant',
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.5,
                  color: Colors.white,
                ),
              ),
              Text(
                'Fully offline · Vosk engine',
                style: TextStyle(
                  fontSize: 13,
                  color: Colors.white.withValues(alpha: 0.5),
                  letterSpacing: 0.3,
                ),
              ),
            ],
          ),
        ),
        _buildStatusChip(),
      ],
    );
  }

  Widget _buildStatusChip() {
    final isActive = _isServiceRunning;
    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
      decoration: BoxDecoration(
        gradient: isActive
            ? const LinearGradient(
                colors: [Color(0xFF00C853), Color(0xFF00E676)])
            : null,
        color: isActive ? null : const Color(0xFF2D2D44),
        borderRadius: BorderRadius.circular(20),
        boxShadow: isActive
            ? [
                BoxShadow(
                    color: const Color(0xFF00C853).withValues(alpha: 0.3),
                    blurRadius: 10)
              ]
            : null,
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: isActive ? Colors.white : Colors.grey,
            ),
          ),
          const SizedBox(width: 6),
          Text(
            isActive ? 'ACTIVE' : 'OFF',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              color: isActive ? Colors.white : Colors.grey,
              letterSpacing: 1,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMicrophoneOrb() {
    return GestureDetector(
      onTap: (_permissionsGranted && _modelReady)
          ? _toggleService
          : (!_permissionsGranted ? _requestPermissions : null),
      child: SizedBox(
        height: 200,
        width: 200,
        child: Stack(
          alignment: Alignment.center,
          children: [
            if (_isServiceRunning) ...[
              AnimatedBuilder(
                animation: _waveController,
                builder: (context, child) {
                  return Container(
                    width: 200 * (0.8 + _waveController.value * 0.4),
                    height: 200 * (0.8 + _waveController.value * 0.4),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: const Color(0xFF6C63FF)
                            .withValues(alpha: 1 - _waveController.value),
                        width: 2,
                      ),
                    ),
                  );
                },
              ),
              AnimatedBuilder(
                animation: _waveController,
                builder: (context, child) {
                  final delayed =
                      (_waveController.value + 0.3).clamp(0.0, 1.0);
                  return Container(
                    width: 200 * (0.7 + delayed * 0.3),
                    height: 200 * (0.7 + delayed * 0.3),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: const Color(0xFF6C63FF)
                            .withValues(alpha: (1 - delayed) * 0.5),
                        width: 1.5,
                      ),
                    ),
                  );
                },
              ),
            ],
            AnimatedBuilder(
              animation: _pulseAnimation,
              builder: (context, child) {
                final scale =
                    _isServiceRunning ? _pulseAnimation.value : 1.0;
                return Transform.scale(
                  scale: scale,
                  child: Container(
                    width: 120,
                    height: 120,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: RadialGradient(
                        colors: _isServiceRunning
                            ? [
                                const Color(0xFF7C4DFF),
                                const Color(0xFF6C63FF),
                                const Color(0xFF3F51B5),
                              ]
                            : [
                                const Color(0xFF2D2D44),
                                const Color(0xFF1A1A2E),
                              ],
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: _isServiceRunning
                              ? const Color(0xFF6C63FF).withValues(alpha: 0.5)
                              : Colors.black26,
                          blurRadius: _isServiceRunning ? 30 : 10,
                          spreadRadius: _isServiceRunning ? 5 : 0,
                        ),
                      ],
                    ),
                    child: Icon(
                      _isServiceRunning ? Icons.mic : Icons.mic_off,
                      size: 48,
                      color: _isServiceRunning
                          ? Colors.white
                          : Colors.white.withValues(alpha: 0.4),
                    ),
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildGlassCard({
    required Widget child,
    EdgeInsets padding = const EdgeInsets.all(20),
  }) {
    return Container(
      width: double.infinity,
      padding: padding,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.05),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: Colors.white.withValues(alpha: 0.08),
          width: 1,
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.2),
            blurRadius: 20,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: child,
    );
  }

  Widget _buildPermissionCard() {
    return _buildGlassCard(
      child: Column(
        children: [
          const Icon(Icons.security, color: Color(0xFFFF6B6B), size: 36),
          const SizedBox(height: 12),
          const Text(
            'Permissions Required',
            style: TextStyle(
              fontSize: 16, fontWeight: FontWeight.w600, color: Colors.white),
          ),
          const SizedBox(height: 8),
          Text(
            'Microphone and notification permissions are needed.',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 13, color: Colors.white.withValues(alpha: 0.6), height: 1.4),
          ),
          const SizedBox(height: 16),
          ElevatedButton.icon(
            onPressed: _requestPermissions,
            icon: const Icon(Icons.check_circle_outline, size: 18),
            label: const Text('Grant Permissions'),
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFFFF6B6B),
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildModelCard() {
    return _buildGlassCard(
      child: Column(
        children: [
          Icon(
            _isDownloading ? Icons.settings : Icons.build_circle_outlined,
            color: const Color(0xFF448AFF),
            size: 36,
          ),
          const SizedBox(height: 12),
          Text(
            _isDownloading
                ? 'Setting Up Speech Engine...'
                : 'First-Time Setup Required',
            style: const TextStyle(
              fontSize: 16, fontWeight: FontWeight.w600, color: Colors.white),
          ),
          const SizedBox(height: 8),
          Text(
            _isDownloading
                ? 'Extracting speech model. This only happens once.'
                : 'Tap below to set up the offline speech engine (no internet needed).',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 13, color: Colors.white.withValues(alpha: 0.6), height: 1.4),
          ),
          const SizedBox(height: 16),
          if (_isDownloading) ...[
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: LinearProgressIndicator(
                value: _downloadProgress / 100.0,
                minHeight: 8,
                backgroundColor: Colors.white.withValues(alpha: 0.1),
                valueColor: const AlwaysStoppedAnimation<Color>(
                    Color(0xFF448AFF)),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '$_downloadProgress%',
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: Color(0xFF448AFF),
              ),
            ),
          ] else
            ElevatedButton.icon(
              onPressed: _downloadModel,
              icon: const Icon(Icons.play_arrow, size: 18),
              label: const Text('Setup Speech Engine'),
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF448AFF),
                foregroundColor: Colors.white,
                padding:
                    const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14)),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildBatteryCard() {
    return _buildGlassCard(
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: const Color(0xFFFFB300).withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(12),
            ),
            child: const Icon(
                Icons.battery_alert, color: Color(0xFFFFB300), size: 24),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Battery Optimization',
                    style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: Colors.white)),
                Text('Disable to keep assistant running',
                    style: TextStyle(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.5))),
              ],
            ),
          ),
          TextButton(
            onPressed: _requestBatteryOptimization,
            child: const Text('FIX',
                style: TextStyle(
                    color: Color(0xFFFFB300), fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }

  Widget _buildPorcupineKeyCard() {
    return _buildGlassCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: _porcupineKeySet
                        ? [const Color(0xFF00C853), const Color(0xFF00E676)]
                        : [const Color(0xFFE040FB), const Color(0xFF7C4DFF)],
                  ),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  _porcupineKeySet ? Icons.vpn_key : Icons.key_off,
                  color: Colors.white,
                  size: 22,
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _porcupineKeySet
                          ? 'Porcupine Key Active'
                          : 'Porcupine Access Key',
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      _porcupineKeySet
                          ? 'Wake word detection is ready'
                          : 'Required for wake word detection',
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.5),
                      ),
                    ),
                  ],
                ),
              ),
              if (_porcupineKeySet)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                  decoration: BoxDecoration(
                    color: const Color(0xFF00C853).withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.check_circle, color: Color(0xFF00C853), size: 14),
                      SizedBox(width: 4),
                      Text(
                        'SET',
                        style: TextStyle(
                          fontSize: 10,
                          fontWeight: FontWeight.w700,
                          color: Color(0xFF00C853),
                          letterSpacing: 1,
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
          const SizedBox(height: 16),

          if (!_porcupineKeySet) ...[
            // Info text
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFFE040FB).withValues(alpha: 0.06),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: const Color(0xFFE040FB).withValues(alpha: 0.12),
                ),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    Icons.info_outline,
                    size: 16,
                    color: Colors.white.withValues(alpha: 0.5),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Each free Porcupine key supports 1 active user. '
                      'Get your own free key from the Picovoice Console.',
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.6),
                        height: 1.4,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),

            // Key input field
            Container(
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.05),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(
                  color: Colors.white.withValues(alpha: 0.1),
                ),
              ),
              child: TextField(
                controller: _porcupineKeyController,
                obscureText: !_isKeyVisible,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 14,
                  fontFamily: 'monospace',
                ),
                decoration: InputDecoration(
                  hintText: 'Paste your Porcupine access key...',
                  hintStyle: TextStyle(
                    color: Colors.white.withValues(alpha: 0.25),
                    fontSize: 13,
                  ),
                  border: InputBorder.none,
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 14,
                  ),
                  prefixIcon: Icon(
                    Icons.vpn_key_outlined,
                    size: 18,
                    color: Colors.white.withValues(alpha: 0.3),
                  ),
                  suffixIcon: IconButton(
                    icon: Icon(
                      _isKeyVisible
                          ? Icons.visibility_off
                          : Icons.visibility,
                      size: 18,
                      color: Colors.white.withValues(alpha: 0.4),
                    ),
                    onPressed: () {
                      setState(() => _isKeyVisible = !_isKeyVisible);
                    },
                  ),
                ),
              ),
            ),
            const SizedBox(height: 14),

            // Action buttons row
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _isLoading ? null : _validateAndSaveKey,
                    icon: _isLoading
                        ? const SizedBox(
                            width: 16, height: 16,
                            child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white))
                        : const Icon(Icons.verified_outlined, size: 18),
                    label: Text(_isLoading ? 'Validating...' : 'Save & Validate'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFF7C4DFF),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 13),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                      ),
                      elevation: 4,
                      shadowColor:
                          const Color(0xFF7C4DFF).withValues(alpha: 0.4),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),

            // Get key link
            Center(
              child: TextButton.icon(
                onPressed: () {
                  _showSnackBar(
                    'Visit: https://console.picovoice.ai/ to get your free key',
                  );
                },
                icon: const Icon(Icons.open_in_new, size: 14),
                label: const Text(
                  'Get a free key from Picovoice Console',
                  style: TextStyle(fontSize: 12),
                ),
                style: TextButton.styleFrom(
                  foregroundColor: const Color(0xFFE040FB),
                ),
              ),
            ),
          ] else ...[
            // Key is set — show compact info with change option
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFF00C853).withValues(alpha: 0.06),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: const Color(0xFF00C853).withValues(alpha: 0.12),
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    Icons.check_circle_outline,
                    size: 16,
                    color: Colors.white.withValues(alpha: 0.5),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      'Key: ${_porcupineKey.substring(0, _porcupineKey.length > 12 ? 12 : _porcupineKey.length)}••••••',
                      style: TextStyle(
                        fontSize: 13,
                        color: Colors.white.withValues(alpha: 0.6),
                        fontFamily: 'monospace',
                      ),
                    ),
                  ),
                  TextButton(
                    onPressed: _clearPorcupineKey,
                    child: const Text(
                      'CHANGE',
                      style: TextStyle(
                        color: Color(0xFFE040FB),
                        fontWeight: FontWeight.w600,
                        fontSize: 11,
                        letterSpacing: 0.8,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildStatusCard() {
    Color statusColor;
    IconData statusIcon;

    switch (_statusMessage.toLowerCase()) {
      case 'listening':
        statusColor = const Color(0xFF00E676);
        statusIcon = Icons.hearing;
        break;
      case 'dictating':
        statusColor = const Color(0xFFFF6B6B);
        statusIcon = Icons.edit_note;
        break;
      case 'error':
        statusColor = const Color(0xFFFF5252);
        statusIcon = Icons.error_outline;
        break;
      case 'starting':
        statusColor = const Color(0xFFFFB300);
        statusIcon = Icons.hourglass_top;
        break;
      default:
        statusColor = const Color(0xFF78909C);
        statusIcon = Icons.power_settings_new;
    }

    final bool canStart = _permissionsGranted && _modelReady && _porcupineKeySet;

    return _buildGlassCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(statusIcon, color: statusColor, size: 20),
              const SizedBox(width: 8),
              Text(
                'Status: $_statusMessage',
                style: TextStyle(
                  fontSize: 15, fontWeight: FontWeight.w600, color: statusColor),
              ),
            ],
          ),
          if (_lastRecognizedText.isNotEmpty) ...[
            const SizedBox(height: 16),
            _buildInfoRow(
                'Last heard', _lastRecognizedText, Icons.record_voice_over),
          ],
          if (_lastAction.isNotEmpty) ...[
            const SizedBox(height: 12),
            _buildInfoRow('Action', _lastAction, Icons.play_circle_outline),
          ],
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _isLoading ? null : (canStart ? _toggleService : null),
              style: ElevatedButton.styleFrom(
                backgroundColor: _isServiceRunning
                    ? const Color(0xFF2D2D44)
                    : const Color(0xFF6C63FF),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 16),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16)),
                elevation: _isServiceRunning ? 0 : 8,
                shadowColor: const Color(0xFF6C63FF).withValues(alpha: 0.4),
              ),
              child: _isLoading
                  ? const SizedBox(
                      width: 20, height: 20,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white))
                  : Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          _isServiceRunning
                              ? Icons.stop_circle_outlined
                              : Icons.play_circle_outline,
                          size: 22,
                        ),
                        const SizedBox(width: 10),
                        Text(
                          _isServiceRunning
                              ? 'Stop Assistant'
                              : 'Enable Assistant',
                          style: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.w600,
                            letterSpacing: 0.3),
                        ),
                      ],
                    ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoRow(String label, String value, IconData icon) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.03),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white.withValues(alpha: 0.05)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 16, color: Colors.white.withValues(alpha: 0.4)),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label,
                    style: TextStyle(
                      fontSize: 11, fontWeight: FontWeight.w600,
                      color: Colors.white.withValues(alpha: 0.4),
                      letterSpacing: 0.8)),
                const SizedBox(height: 4),
                Text(value,
                    style: const TextStyle(
                      fontSize: 14, color: Colors.white, height: 1.3),
                    softWrap: true,
                    overflow: TextOverflow.visible,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCommandsCard() {
    return _buildGlassCard(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
      child: Theme(
        data: Theme.of(context).copyWith(
          dividerColor: Colors.transparent,
          splashColor: Colors.transparent,
          highlightColor: Colors.transparent,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Row(
              children: [
                Icon(Icons.terminal, color: Color(0xFF6C63FF), size: 20),
                SizedBox(width: 8),
                Text('Voice Commands',
                    style: TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w600,
                      color: Colors.white)),
                Spacer(),
                Icon(Icons.touch_app_outlined,
                    size: 14, color: Color(0xFF6C63FF)),
                SizedBox(width: 4),
                Text('Tap to expand',
                    style: TextStyle(fontSize: 11, color: Color(0xFF6C63FF))),
              ],
            ),
            const SizedBox(height: 12),

            // ── App Launching ──
            _buildCommandCategory(
              title: 'App Launching',
              icon: Icons.apps_rounded,
              color: const Color(0xFF6C63FF),
              commands: [
                _CmdEntry('"Open [App Name]"', 'Opens any installed app on your phone', Icons.open_in_new),
                _CmdEntry('"Launch [App]"', 'Same as open — works with launch, start, run', Icons.rocket_launch_outlined),
                _CmdEntry('"[App Name]"', 'Just say the name — no prefix needed', Icons.mic_outlined),
                _CmdEntry('"Open Gallery"', 'Opens your photo gallery', Icons.photo_library_outlined),
                _CmdEntry('"Open Camera"', 'Launches the camera app', Icons.camera_alt_outlined),
              ],
            ),

            const SizedBox(height: 6),

            // ── Browser & Search ──
            _buildCommandCategory(
              title: 'Browser & Search',
              icon: Icons.travel_explore,
              color: const Color(0xFF00BCD4),
              commands: [
                _CmdEntry('"Search [anything]"', 'Google search for any query', Icons.search),
                _CmdEntry('"Search how to cook pasta"', 'Example: searches Google directly', Icons.restaurant_outlined),
                _CmdEntry('"Open Chrome"', 'Opens the Chrome browser', Icons.public_outlined),
                _CmdEntry('"Open Browser"', 'Same — opens default browser', Icons.language_outlined),
              ],
            ),

            const SizedBox(height: 6),

            // ── Voice Notes ──
            _buildCommandCategory(
              title: 'Voice Notes',
              icon: Icons.sticky_note_2_outlined,
              color: const Color(0xFFFFB300),
              commands: [
                _CmdEntry('"Take Notes"', 'Start voice dictation — everything you say is recorded', Icons.note_add_outlined),
                _CmdEntry('"New Note" / "Dictate"', 'Alternative ways to start dictation', Icons.edit_note_outlined),
                _CmdEntry('"Stop Notes"', 'Save the note and stop recording', Icons.save_outlined),
                _CmdEntry('"Stop Saving" / "Save It"', 'Alternative ways to save and stop', Icons.check_circle_outline),
                _CmdEntry('"Notes" / "Show Notes"', 'Opens the full notes page in the app', Icons.folder_open_outlined),
                _CmdEntry('"Read Notes"', 'Shows your saved notes', Icons.visibility_outlined),
              ],
            ),

            const SizedBox(height: 6),

            // ── Quick Actions ──
            _buildCommandCategory(
              title: 'Quick Actions',
              icon: Icons.flash_on_outlined,
              color: const Color(0xFF00E676),
              commands: [
                _CmdEntry('Wake Word: "Porcupine"', 'Say this first to wake up the assistant', Icons.record_voice_over_outlined),
                _CmdEntry('20s Auto-Sleep', 'Assistant sleeps after 20 seconds of no command', Icons.bedtime_outlined),
                _CmdEntry('Chain Commands', 'After a command, keep talking — no need to wake again', Icons.link_outlined),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCommandCategory({
    required String title,
    required IconData icon,
    required Color color,
    required List<_CmdEntry> commands,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: color.withValues(alpha: 0.1)),
      ),
      child: ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 2),
        childrenPadding: const EdgeInsets.only(left: 14, right: 14, bottom: 12),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        collapsedShape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        leading: Container(
          padding: const EdgeInsets.all(7),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(9),
          ),
          child: Icon(icon, size: 16, color: color),
        ),
        title: Text(title,
            style: TextStyle(
              fontSize: 13, fontWeight: FontWeight.w600,
              color: color)),
        trailing: Icon(Icons.expand_more, size: 20, color: color.withValues(alpha: 0.6)),
        iconColor: color,
        collapsedIconColor: color.withValues(alpha: 0.6),
        children: commands
            .map((cmd) => Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: _buildCommandRow(cmd.command, cmd.desc, cmd.icon, color),
                ))
            .toList(),
      ),
    );
  }

  Widget _buildCommandRow(String command, String desc, IconData icon, Color accentColor) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          margin: const EdgeInsets.only(top: 2),
          padding: const EdgeInsets.all(6),
          decoration: BoxDecoration(
            color: accentColor.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Icon(icon, size: 14, color: accentColor.withValues(alpha: 0.7)),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(command,
                  style: TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w600,
                    color: accentColor.withValues(alpha: 0.9))),
              const SizedBox(height: 1),
              Text(desc,
                  style: TextStyle(
                    fontSize: 11,
                    color: Colors.white.withValues(alpha: 0.35),
                    height: 1.3)),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildNotesCard() {
    return _buildGlassCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.sticky_note_2_outlined,
                  color: Color(0xFFFFB300), size: 20),
              const SizedBox(width: 8),
              const Expanded(
                child: Text('Saved Notes',
                    style: TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w600,
                      color: Colors.white)),
              ),
              TextButton.icon(
                onPressed: _loadNotes,
                icon: const Icon(Icons.refresh, size: 16),
                label: const Text('Refresh', style: TextStyle(fontSize: 12)),
                style: TextButton.styleFrom(
                    foregroundColor: const Color(0xFFFFB300)),
              ),
            ],
          ),
          if (_notes.isEmpty) ...[
            const SizedBox(height: 16),
            Center(
              child: Column(
                children: [
                  Icon(Icons.note_outlined,
                      size: 40, color: Colors.white.withValues(alpha: 0.15)),
                  const SizedBox(height: 8),
                  Text(
                    'No notes yet.\nSay "take notes" to start dictating.',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 13,
                      color: Colors.white.withValues(alpha: 0.3),
                      height: 1.4),
                  ),
                ],
              ),
            ),
          ] else ...[
            const SizedBox(height: 12),
            ...(_notes.take(3).map((note) => _buildNoteItem(note))),
            if (_notes.length > 3) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: TextButton.icon(
                  onPressed: () {
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => NotesPage(
                          notes: _notes,
                          onRefresh: _loadNotes,
                        ),
                      ),
                    );
                  },
                  icon: const Icon(Icons.visibility_outlined, size: 16),
                  label: Text(
                    'View All ${_notes.length} Notes',
                    style: const TextStyle(fontSize: 13),
                  ),
                  style: TextButton.styleFrom(
                    foregroundColor: const Color(0xFFFFB300),
                    padding: const EdgeInsets.symmetric(vertical: 10),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                      side: BorderSide(
                          color: const Color(0xFFFFB300).withValues(alpha: 0.2)),
                    ),
                  ),
                ),
              ),
            ],
          ],
        ],
      ),
    );
  }

  Widget _buildNoteItem(Map<String, dynamic> note) {
    final content = (note['content'] as String?) ?? '';
    final modified = (note['modified'] as String?) ?? '';
    final name = (note['name'] as String?) ?? '';

    final lines = content.split('\n');
    final noteText = lines.length > 4
        ? lines.sublist(4).join('\n').trim()
        : content.trim();

    return GestureDetector(
      onTap: () {
        Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => NoteDetailPage(note: note),
          ),
        );
      },
      child: Container(
        margin: const EdgeInsets.only(bottom: 10),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.03),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.white.withValues(alpha: 0.05)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.description,
                    size: 14, color: Color(0xFFFFB300)),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(name,
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.white.withValues(alpha: 0.5),
                        fontWeight: FontWeight.w500)),
                ),
                Text(modified,
                    style: TextStyle(
                        fontSize: 10,
                        color: Colors.white.withValues(alpha: 0.3))),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              noteText.length > 150
                  ? '${noteText.substring(0, 150)}...'
                  : noteText,
              style: TextStyle(
                fontSize: 13,
                color: Colors.white.withValues(alpha: 0.7),
                height: 1.4),
            ),
          ],
        ),
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════════
// Helper class for command entries
// ═══════════════════════════════════════════════════════════════

class _CmdEntry {
  final String command;
  final String desc;
  final IconData icon;
  const _CmdEntry(this.command, this.desc, this.icon);
}

// ═══════════════════════════════════════════════════════════════
// Full Notes Page – shown when user says "notes" / "show notes"
// ═══════════════════════════════════════════════════════════════

class NotesPage extends StatefulWidget {
  final List<Map<String, dynamic>> notes;
  final Future<void> Function() onRefresh;

  const NotesPage({super.key, required this.notes, required this.onRefresh});

  @override
  State<NotesPage> createState() => _NotesPageState();
}

class _NotesPageState extends State<NotesPage> {
  late List<Map<String, dynamic>> _notes;

  @override
  void initState() {
    super.initState();
    _notes = List.from(widget.notes);
  }

  Future<void> _refresh() async {
    await widget.onRefresh();
    // Notes are refreshed in the parent; we get them on next build
  }

  String _extractNoteText(Map<String, dynamic> note) {
    final content = (note['content'] as String?) ?? '';
    final lines = content.split('\n');
    return lines.length > 4
        ? lines.sublist(4).join('\n').trim()
        : content.trim();
  }

  int _wordCount(String text) {
    return text.trim().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).length;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFF0D0D1A),
              Color(0xFF1A1A2E),
              Color(0xFF16213E),
              Color(0xFF0F3460),
            ],
            stops: [0.0, 0.3, 0.6, 1.0],
          ),
        ),
        child: SafeArea(
          child: Column(
            children: [
              // ── Header ──
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Row(
                  children: [
                    IconButton(
                      onPressed: () => Navigator.of(context).pop(),
                      icon: const Icon(Icons.arrow_back_ios_new,
                          color: Colors.white, size: 20),
                      style: IconButton.styleFrom(
                        backgroundColor: Colors.white.withValues(alpha: 0.08),
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12)),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text('Voice Notes',
                              style: TextStyle(
                                fontSize: 20, fontWeight: FontWeight.w700,
                                color: Colors.white, letterSpacing: -0.3)),
                          Text('${_notes.length} note${_notes.length != 1 ? 's' : ''} saved',
                              style: TextStyle(
                                fontSize: 13,
                                color: Colors.white.withValues(alpha: 0.5))),
                        ],
                      ),
                    ),
                    Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(
                          colors: [Color(0xFFFFB300), Color(0xFFFF8F00)],
                        ),
                        borderRadius: BorderRadius.circular(14),
                        boxShadow: [
                          BoxShadow(
                            color: const Color(0xFFFFB300).withValues(alpha: 0.3),
                            blurRadius: 12,
                            offset: const Offset(0, 4),
                          ),
                        ],
                      ),
                      child: const Icon(Icons.sticky_note_2,
                          color: Colors.white, size: 22),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              // ── Notes List ──
              Expanded(
                child: _notes.isEmpty
                    ? Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.note_outlined,
                                size: 64,
                                color: Colors.white.withValues(alpha: 0.1)),
                            const SizedBox(height: 16),
                            Text('No notes yet',
                                style: TextStyle(
                                    fontSize: 18,
                                    fontWeight: FontWeight.w600,
                                    color: Colors.white.withValues(alpha: 0.3))),
                            const SizedBox(height: 8),
                            Text(
                              'Say "take notes" to start dictating',
                              style: TextStyle(
                                  fontSize: 14,
                                  color: Colors.white.withValues(alpha: 0.2)),
                            ),
                          ],
                        ),
                      )
                    : ListView.builder(
                        physics: const BouncingScrollPhysics(),
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        itemCount: _notes.length,
                        itemBuilder: (context, index) {
                          final note = _notes[index];
                          final noteText = _extractNoteText(note);
                          final modified = (note['modified'] as String?) ?? '';
                          final name = (note['name'] as String?) ?? '';
                          final words = _wordCount(noteText);

                          return GestureDetector(
                            onTap: () {
                              Navigator.of(context).push(
                                MaterialPageRoute(
                                  builder: (_) => NoteDetailPage(note: note),
                                ),
                              );
                            },
                            child: Container(
                              margin: const EdgeInsets.only(bottom: 12),
                              padding: const EdgeInsets.all(16),
                              decoration: BoxDecoration(
                                color: Colors.white.withValues(alpha: 0.05),
                                borderRadius: BorderRadius.circular(16),
                                border: Border.all(
                                    color: Colors.white.withValues(alpha: 0.08)),
                                boxShadow: [
                                  BoxShadow(
                                    color: Colors.black.withValues(alpha: 0.15),
                                    blurRadius: 10,
                                    offset: const Offset(0, 4),
                                  ),
                                ],
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    children: [
                                      Container(
                                        padding: const EdgeInsets.all(6),
                                        decoration: BoxDecoration(
                                          color: const Color(0xFFFFB300)
                                              .withValues(alpha: 0.15),
                                          borderRadius:
                                              BorderRadius.circular(8),
                                        ),
                                        child: const Icon(Icons.description,
                                            size: 14,
                                            color: Color(0xFFFFB300)),
                                      ),
                                      const SizedBox(width: 8),
                                      Expanded(
                                        child: Text(name,
                                            style: TextStyle(
                                                fontSize: 12,
                                                color: Colors.white
                                                    .withValues(alpha: 0.5),
                                                fontWeight: FontWeight.w500)),
                                      ),
                                      Container(
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 8, vertical: 3),
                                        decoration: BoxDecoration(
                                          color: const Color(0xFF6C63FF)
                                              .withValues(alpha: 0.15),
                                          borderRadius:
                                              BorderRadius.circular(8),
                                        ),
                                        child: Text('$words words',
                                            style: const TextStyle(
                                                fontSize: 10,
                                                color: Color(0xFF6C63FF),
                                                fontWeight: FontWeight.w600)),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 10),
                                  Text(
                                    noteText.length > 200
                                        ? '${noteText.substring(0, 200)}...'
                                        : noteText,
                                    style: TextStyle(
                                        fontSize: 14,
                                        color: Colors.white
                                            .withValues(alpha: 0.75),
                                        height: 1.5),
                                  ),
                                  const SizedBox(height: 10),
                                  Row(
                                    children: [
                                      Icon(Icons.access_time,
                                          size: 12,
                                          color: Colors.white
                                              .withValues(alpha: 0.3)),
                                      const SizedBox(width: 4),
                                      Text(modified,
                                          style: TextStyle(
                                              fontSize: 11,
                                              color: Colors.white
                                                  .withValues(alpha: 0.3))),
                                      const Spacer(),
                                      Icon(Icons.chevron_right,
                                          size: 18,
                                          color: Colors.white
                                              .withValues(alpha: 0.2)),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          );
                        },
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════════
// Note Detail Page – shows full note content
// ═══════════════════════════════════════════════════════════════

class NoteDetailPage extends StatelessWidget {
  final Map<String, dynamic> note;

  const NoteDetailPage({super.key, required this.note});

  String _extractNoteText() {
    final content = (note['content'] as String?) ?? '';
    final lines = content.split('\n');
    return lines.length > 4
        ? lines.sublist(4).join('\n').trim()
        : content.trim();
  }

  int _wordCount(String text) {
    return text.trim().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).length;
  }

  @override
  Widget build(BuildContext context) {
    final noteText = _extractNoteText();
    final modified = (note['modified'] as String?) ?? '';
    final name = (note['name'] as String?) ?? '';
    final words = _wordCount(noteText);

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFF0D0D1A),
              Color(0xFF1A1A2E),
              Color(0xFF16213E),
              Color(0xFF0F3460),
            ],
            stops: [0.0, 0.3, 0.6, 1.0],
          ),
        ),
        child: SafeArea(
          child: Column(
            children: [
              // ── Header ──
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Row(
                  children: [
                    IconButton(
                      onPressed: () => Navigator.of(context).pop(),
                      icon: const Icon(Icons.arrow_back_ios_new,
                          color: Colors.white, size: 20),
                      style: IconButton.styleFrom(
                        backgroundColor: Colors.white.withValues(alpha: 0.08),
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12)),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(name,
                              style: const TextStyle(
                                fontSize: 16, fontWeight: FontWeight.w600,
                                color: Colors.white)),
                          Text('$words words · $modified',
                              style: TextStyle(
                                fontSize: 12,
                                color: Colors.white.withValues(alpha: 0.5))),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              // ── Content ──
              Expanded(
                child: SingleChildScrollView(
                  physics: const BouncingScrollPhysics(),
                  padding: const EdgeInsets.all(20),
                  child: Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.05),
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(
                          color: Colors.white.withValues(alpha: 0.08)),
                    ),
                    child: SelectableText(
                      noteText,
                      style: TextStyle(
                        fontSize: 16,
                        color: Colors.white.withValues(alpha: 0.85),
                        height: 1.7,
                        letterSpacing: 0.2,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
