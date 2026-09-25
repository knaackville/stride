// The plain backdrop behind the virtual track (issue #30), and the way back out of it.
//
// The behaviour worth pinning here is not that the launcher can be hidden — it is that it can
// always be got back, and that it is never hidden for a track that is not on screen. This console
// has no physical Home or Back button, so a launcher that blanks itself and offers nothing is a
// console the rider cannot operate, and that failure is silent: nothing throws, and a screenshot
// of it looks like a device that is switched off.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stride_spikes/bridge.dart';
import 'package:stride_spikes/model/profile_store.dart';
import 'package:stride_spikes/screens/launcher_home.dart';

const MethodChannel _channel = MethodChannel('io.stride.spikes/bridge');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Map<String, Object?> floor;
  late List<Map<String, Object?>> backdropWrites;

  /// What the host would say if asked. WorkoutController re-reads the host on
  /// every push, so a mock that only delivered the push would have the session
  /// fall straight back to idle and the edge out of idle would fire twice.
  late String workoutState;

  setUp(() {
    // WorkoutController hangs its host-push callback off SpikeBridge, and the
    // host's workout-state notification is what these tests stand in for.
    SpikeBridge.install();
    workoutState = 'idle';
    backdropWrites = <Map<String, Object?>>[];
    floor = <String, Object?>{
      'on': false,
      'visible': false,
      'chosen': null,
      'backdrop': false,
      'videoPlaying': false,
    };

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
          switch (call.method) {
            case 'trackFloorGet':
              return floor;
            case 'trackBackdropSet':
              backdropWrites.add(
                Map<String, Object?>.from(call.arguments as Map),
              );
              return true;
            case 'workoutState':
              return workoutState;
            case 'listApps':
              return <Object?>[];
            case 'appstoreStatus':
              return <String, Object?>{
                'initialization': 'ready',
                'items': <Object?>[],
              };
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  /// Stand in for the host announcing a workout state change, which is both a
  /// push and a new answer to every subsequent poll.
  Future<void> hostSaysWorkout(WidgetTester tester, String state) async {
    workoutState = state;
    SpikeBridge.onWorkoutStateChanged!(state);
    await tester.pumpAndSettle();
  }

  Future<void> pumpLauncher(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: LauncherHome(profiles: ProfileStore(storage: _MemoryStorage())),
      ),
    );
    await tester.pumpAndSettle();
  }

  /// The default, and the one that matters most: nobody who has not asked for
  /// this sees any change at all.
  testWidgets('the launcher is left alone until the rider opts in', (
    tester,
  ) async {
    await pumpLauncher(tester);

    expect(find.text('Your workout apps, one tap away.'), findsOneWidget);
    expect(find.text('Show apps'), findsNothing);
  });

  testWidgets('a plain backdrop replaces the launcher content', (tester) async {
    floor = <String, Object?>{'on': true, 'visible': true, 'backdrop': true};
    await pumpLauncher(tester);

    // The promotional copy the issue is about, and the app grid with it.
    expect(find.text('Your workout apps, one tap away.'), findsNothing);
    expect(find.text('Pinned apps'), findsNothing);
    expect(find.text('Show apps'), findsOneWidget);
  });

  /// The escape hatch, tapped where a rider would tap it: on empty space, not
  /// on the button. `HitTestBehavior.opaque` is what makes that count.
  testWidgets('tapping the backdrop anywhere brings the launcher back', (
    tester,
  ) async {
    floor = <String, Object?>{'on': true, 'visible': true, 'backdrop': true};
    await pumpLauncher(tester);

    await tester.tapAt(const Offset(640, 620));
    await tester.pumpAndSettle();

    expect(find.text('Your workout apps, one tap away.'), findsOneWidget);
    expect(find.text('Show apps'), findsNothing);
  });

  testWidgets('the labelled button brings the launcher back too', (
    tester,
  ) async {
    floor = <String, Object?>{'on': true, 'visible': true, 'backdrop': true};
    await pumpLauncher(tester);

    await tester.tap(find.text('Show apps'));
    await tester.pumpAndSettle();

    expect(find.text('Your workout apps, one tap away.'), findsOneWidget);
  });

  /// The overlay's Home button is the second way out, and the only one that
  /// does not require guessing that a blank screen is tappable.
  testWidgets('the overlay Home button reveals the launcher', (tester) async {
    floor = <String, Object?>{'on': true, 'visible': true, 'backdrop': true};
    final key = GlobalKey<LauncherHomeState>();
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: LauncherHome(
          key: key,
          profiles: ProfileStore(storage: _MemoryStorage()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Show apps'), findsOneWidget);

    key.currentState!.resetToLauncherRoot();
    await tester.pumpAndSettle();

    expect(find.text('Your workout apps, one tap away.'), findsOneWidget);
  });

  /// The bug this was reported as: fine after a reboot, wrong after anyone had
  /// used the treadmill. With the track floor set to "always on" the rider has
  /// to tap through the plain backdrop to reach Start, so starting a workout is
  /// the one moment a reveal is guaranteed to be stale — and nothing else that
  /// drops one (leaving Stride, popping a route) happens during a workout, so
  /// the app grid sat behind the track until the console was rebooted.
  testWidgets('starting a workout puts the backdrop back', (tester) async {
    floor = <String, Object?>{'on': true, 'visible': true, 'backdrop': true};
    await pumpLauncher(tester);

    await tester.tap(find.text('Show apps'));
    await tester.pumpAndSettle();
    expect(find.text('Pinned apps'), findsOneWidget);

    await hostSaysWorkout(tester, 'running');

    expect(find.text('Pinned apps'), findsNothing);
    expect(find.text('Show apps'), findsOneWidget);
  });

  /// The other half of the same rule: the backdrop must not re-arm itself while
  /// a workout is already running. The controller notifies once a second, so a
  /// launcher that re-blanked on the *level* rather than the edge would swallow
  /// the rider's tap within a second of every attempt and leave the app grid
  /// unreachable for the whole run.
  testWidgets('a tap mid-workout keeps the launcher up', (tester) async {
    floor = <String, Object?>{'on': true, 'visible': true, 'backdrop': true};
    await pumpLauncher(tester);

    await hostSaysWorkout(tester, 'running');

    await tester.tap(find.text('Show apps'));
    await tester.pumpAndSettle();

    await hostSaysWorkout(tester, 'running');

    expect(find.text('Pinned apps'), findsOneWidget);
  });

  /// The other lever that can be "unselected": Track floor's own tri-state
  /// choice ("Automatic", not "Always on" — see `_TrackFloorChoice`).
  /// Automatic ties the floor's very existence to the workout, so the host
  /// reports `visible: false` the moment a workout ends and [_blankBackdrop]
  /// itself goes false — the grid comes back on its own, with no reveal to
  /// drop. What this pins is that a *second* workout, starting from that
  /// already-shown grid with nobody having tapped anything in between, blanks
  /// it again exactly as the first one did.
  testWidgets('a workout after an automatic track floor also reblanks', (
    tester,
  ) async {
    floor = <String, Object?>{
      'on': true,
      'visible': false,
      'chosen': null,
      'backdrop': true,
    };
    await pumpLauncher(tester);
    expect(find.text('Your workout apps, one tap away.'), findsOneWidget);

    // Workout 1: the automatic floor appears, and the backdrop blanks the
    // launcher without the rider having chosen "Always on" or tapped anything.
    floor = <String, Object?>{
      'on': true,
      'visible': true,
      'chosen': null,
      'backdrop': true,
    };
    await hostSaysWorkout(tester, 'running');
    expect(find.text('Show apps'), findsOneWidget);

    // Workout 1 ends: the automatic floor disappears and the grid comes back
    // on its own — nobody tapped the backdrop or the Home button.
    floor = <String, Object?>{
      'on': true,
      'visible': false,
      'chosen': null,
      'backdrop': true,
    };
    await hostSaysWorkout(tester, 'idle');
    expect(find.text('Your workout apps, one tap away.'), findsOneWidget);

    // Workout 2 starts from that untouched grid.
    floor = <String, Object?>{
      'on': true,
      'visible': true,
      'chosen': null,
      'backdrop': true,
    };
    await hostSaysWorkout(tester, 'running');
    expect(find.text('Show apps'), findsOneWidget);
    expect(find.text('Your workout apps, one tap away.'), findsNothing);
  });

  /// Asking for the track and the track being drawn are different facts. The
  /// overlay can be denied its window, killed, or told to hide its chrome, and
  /// blanking the launcher on the strength of the *choice* would leave the
  /// rider looking at a console with nothing on it and no way to ask for the
  /// app grid except a gesture nobody told them about.
  testWidgets('a wanted-but-not-drawn track floor never blanks the launcher', (
    tester,
  ) async {
    floor = <String, Object?>{'on': true, 'visible': false, 'backdrop': true};
    await pumpLauncher(tester);

    expect(find.text('Your workout apps, one tap away.'), findsOneWidget);
    expect(find.text('Show apps'), findsNothing);
  });

  /// A console with no bridge at all is not evidence that a track is on screen.
  testWidgets('a platform that will not answer leaves the launcher up', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
          if (call.method == 'trackFloorGet') {
            throw PlatformException(code: 'unavailable');
          }
          if (call.method == 'appstoreStatus') {
            return <String, Object?>{
              'initialization': 'ready',
              'items': <Object?>[],
            };
          }
          return call.method == 'listApps' ? <Object?>[] : null;
        });

    await pumpLauncher(tester);

    expect(find.text('Your workout apps, one tap away.'), findsOneWidget);
  });
}

class _MemoryStorage implements ProfileStorage {
  String? _contents;

  @override
  Future<String?> read() async => _contents;

  @override
  Future<void> write(String contents) async => _contents = contents;
}
