package com.focus.launcher.policy

import android.app.admin.DeviceAdminReceiver

/**
 * Required by DevicePolicyManager. Provisioned once via:
 *   adb shell dpm set-device-owner com.focus.launcher/.policy.FocusDeviceAdminReceiver
 * on a device with no accounts configured.
 */
class FocusDeviceAdminReceiver : DeviceAdminReceiver()
