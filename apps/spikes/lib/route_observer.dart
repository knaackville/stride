import 'package:flutter/material.dart';

/// Lets [LauncherHomeState] tell whether Settings, All apps, or anything else
/// it pushed is currently covering its own route, so it can tell the platform
/// to stop drawing the track floor over a screen that is not the home route.
///
/// Its own file rather than `main.dart`, so `launcher_home.dart` can use it
/// without importing the file that already imports `launcher_home.dart`.
final RouteObserver<PageRoute<dynamic>> routeObserver =
    RouteObserver<PageRoute<dynamic>>();
