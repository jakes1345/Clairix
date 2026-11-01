package app.clairix

import androidx.test.runner.AndroidJUnitRunner

/**
 * The instrumentation for tests that end the app's own process. Declared in
 * src/androidTest/AndroidManifest.xml to run in a process of the app's own,
 * app.clairix:instrumented, instead of the main one: the duress wipe ends the
 * process that showed the old data and brings the lock screen back in a new
 * one ([app.clairix.security.WipeRelaunchActivity]), and a test running in the
 * main process would go with it. The emulator workflow runs it on its own:
 *
 *   am instrument -w -e class app.clairix.security.DuressFlowDeviceTest -e duress true \
 *       app.clairix.test/app.clairix.SeparateProcessRunner
 */
class SeparateProcessRunner : AndroidJUnitRunner()
