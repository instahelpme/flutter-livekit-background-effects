/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import 'package:flutter/material.dart';
import 'package:livekit_background_effects/livekit_background_effects.dart';
import 'package:livekit_components/livekit_components.dart';

class Blurring extends StatefulWidget {
  const Blurring({super.key});

  @override
  State<Blurring> createState() => _BlurringState();
}

class _BlurringState extends State<Blurring> {
  late final LivekitBackgroundEffects _processor;

  @override
  void initState() {
    super.initState();

    _processor = LivekitBackgroundEffects();
  }

  @override
  Widget build(BuildContext context) {
    final roomContext = RoomContext.of(context)!;
    void switchToBackground(bg) async {
      final track = roomContext
          .localParticipant
          ?.videoTrackPublications
          .firstOrNull
          ?.track;
      if (track?.processor != _processor) {
        await track?.setProcessor(_processor);
      }
      if (!mounted) return;

      await _processor.updateBackground(bg);
    }

    return Wrap(
      children: [
        FilledButton(
          onPressed: () =>
              switchToBackground(LivekitBackgroundEffectsOptions.none()),
          child: Text("none"),
        ),
        FilledButton(
          onPressed: () => switchToBackground(
            LivekitBackgroundEffectsOptions.lightBlurring(),
          ),
          child: Text("light"),
        ),
        FilledButton(
          onPressed: () => switchToBackground(
            LivekitBackgroundEffectsOptions.lightBlurring(),
          ),
          child: Text("heavy"),
        ),
        FilledButton(
          onPressed: () => switchToBackground(
            LivekitBackgroundEffectsOptions.virtualBackground(
              BackgroundPresets.mountains,
            ),
          ),
          child: Text("outside"),
        ),
      ],
    );
  }
}
