# AndroidKlipper hardening candidate — October 8, 2026

**Status: prerelease candidate; device acceptance pending.** This is not a claim
that a new long print, screen-off test or on-device graph check has passed.

## Baselines and scope

- Previous main: `15f180c410a474864aab3f0de53ae4250ae76597`.
- Frozen clean-device baseline: `generic-onboarding-stable-2026-10-03`.
- Main remains unchanged until the device checks below pass.
- Cameras, Pico transport and printer timing changes are excluded.

## Changes

- Incorporate the lifecycle work from draft PR #8: isolate Mainsail's WebView
  process and avoid re-promoting an already foreground host on task removal.
- Keep screen-awake ON by default; deliver its setting through the launch Intent
  to avoid stale cross-process preferences. Refresh battery optimization status
  when returning from Android settings.
- Poll battery in the background at most every five seconds, with one request
  in flight and a two-second timeout. Temperature-chart updates never await it.
  Expire readings after 30 seconds; unknown values are gaps. Support battery
  chart updates when no printer temperature sensors are available.
- Build Mainsail from pinned source and readable patches in CI; generated assets
  no longer need hand-maintained copies in Git.
- Add behavioral regression tests using the actual patched metadata worker and
  upstream parser, plus battery sampler/chart tests. Update stale architecture
  and code-tour descriptions.

## Automated evidence

Local tests passed for six metadata cases: layers, estimated time, PNG thumbnail
extraction, nested paths/source preservation, missing-file recovery, malformed
UFP exit containment, and asynchronous storage (some checks share a test).
Six battery tests cover valid/zero/full, stalled/overlapping requests, stale
samples, invalid/absent readings, late responses, and sensorless chart updates.
Strict TypeScript checking and the Mainsail production build passed locally.
Project invariants passed locally. The attached candidate is published only after
CI also passes Android unit tests, APK build and package inspection.

These are synthetic parser fixtures, not measurements of live print ETA accuracy.
SVG preview rendering and the installed Android WebView still need device checks.

## Device acceptance before stable promotion

The Windows desktop was offline during preparation; no device was installed,
restarted, moved, heated or used for a new print by this pass.

1. With the printer idle, capture the installed version, config/database backup,
   host logs and current runtime checkpoint before installing the candidate.
   Confirm signing compatibility; do not uninstall to work around a mismatch.
2. Verify Mainsail local and LAN access. Refresh/reopen/reconnect the UI and check
   battery plotting, including 0/100 edge cases if naturally available. Toggle
   the wake setting and reopen the kiosk to confirm it takes effect; leave it ON
   for the G2 and other devices whose sleep behavior is unproven.
3. Swipe away/reopen the UI while idle. Confirm host continuity, USB sessions,
   and Klippy/Moonraker readiness. Check the separate kiosk process and logs.
4. Upload representative Prusa/Orca/Bambu G-code without starting it; verify layer
   total, estimated time, thumbnail/preview and metadata survival after restart.
5. With explicit authorization for printer movement, run a representative long
   print plus idle soak. Check Timer-too-close, disconnects, invalid bytes,
   retransmit changes and service continuity against the known-good baseline.
6. Record build SHA, APK hash, device/hub/power combination and results here.
   Then promote the tested candidate; retain the old release/checkpoint.

## Repository cleanup

PR #8 is superseded by this candidate after bringing its changes forward and
fixing the cross-process setting. Historical onboarding/storage PRs whose branch
heads are already ancestors of main can be closed as incorporated. Camera PR #4
stays separate. No experiment branch should be merged solely to clear the list.
