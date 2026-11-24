# pGPS publication review

Reviewed 2026-11-16. Source commit: unavailable (no readable Git metadata).

- Owner must confirm developer identity/contact, production support and privacy URLs, countries, pricing, ads, target age and content rating.
- Audit runtime traffic, dependencies and final merged release manifest before signing off Data safety. Source manifest alone is not a complete data audit.
- Create a signed release AAB, test the release on a device and complete the Play Console testing/review requirements applicable to this account. No release binary was built by this documentation task.

## Source evidence

Manifest: `app/src/main/AndroidManifest.xml`. Build: `app/build.gradle.kts`.

- network: `app/src/main/java/x/vladgba/gps/MainActivity.java`, `app/src/main/java/x/vladgba/gps/orbit/OrbitCatalog.java`, `app/src/main/java/x/vladgba/gps/ui/AboutDialog.java`, `app/src/main/java/x/vladgba/gps/ui/WaypointsPage.java`
- storage: `app/src/main/java/x/vladgba/gps/MainActivity.java`, `app/src/main/java/x/vladgba/gps/nav/WaypointStore.java`, `app/src/main/java/x/vladgba/gps/orbit/OrbitCatalog.java`, `app/src/main/java/x/vladgba/gps/record/RinexWriter.java`, `app/src/main/java/x/vladgba/gps/record/SessionStore.java`, `app/src/main/java/x/vladgba/gps/record/SessionWriter.java`, `app/src/main/java/x/vladgba/gps/ui/Sky3DView.java`, `app/src/main/java/x/vladgba/gps/ui/SpoofPage.java`, `app/src/main/java/x/vladgba/gps/ui/VoiceCodePage.java`
- sharing: `app/src/main/java/x/vladgba/gps/MainActivity.java`, `app/src/main/java/x/vladgba/gps/ui/RecorderPage.java`, `app/src/main/java/x/vladgba/gps/ui/VoiceCodePage.java`, `app/src/main/java/x/vladgba/gps/ui/WaypointsPage.java`
- learning_clipboard: No matches in scanned module sources; absence is not proof.
- speech_translation: No matches in scanned module sources; absence is not proof.
