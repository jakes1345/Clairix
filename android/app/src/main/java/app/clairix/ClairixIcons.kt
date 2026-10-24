/*
 * Icon path data below is copied from the Material Icons set as published in
 * androidx.compose.material:material-icons-extended 1.7.5.
 *
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.clairix

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The Material icons the app uses that are not in material-icons-core.
 *
 * Only these few are carried, instead of depending on material-icons-extended:
 * that artifact holds several thousand icons, and with R8 off for release
 * builds every one of them would ship in the APK. Each icon is built once, on
 * first use, exactly as the extended artifact builds it.
 */
internal object ClairixIcons {
    val Bluetooth: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Bluetooth") {
            materialPath {
                moveTo(17.71f, 7.71f)
                lineTo(12.0f, 2.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(7.59f)
                lineTo(6.41f, 5.0f)
                lineTo(5.0f, 6.41f)
                lineTo(10.59f, 12.0f)
                lineTo(5.0f, 17.59f)
                lineTo(6.41f, 19.0f)
                lineTo(11.0f, 14.41f)
                verticalLineTo(22.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(5.71f, -5.71f)
                lineToRelative(-4.3f, -4.29f)
                lineToRelative(4.3f, -4.29f)
                close()
                moveTo(13.0f, 5.83f)
                lineToRelative(1.88f, 1.88f)
                lineTo(13.0f, 9.59f)
                verticalLineTo(5.83f)
                close()
                moveTo(14.88f, 16.29f)
                lineTo(13.0f, 18.17f)
                verticalLineToRelative(-3.76f)
                lineToRelative(1.88f, 1.88f)
                close()
            }
        }
    }
    val BluetoothSearching: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.BluetoothSearching",
                autoMirror = true) {
            materialPath {
                moveTo(14.24f, 12.01f)
                lineToRelative(2.32f, 2.32f)
                curveToRelative(0.28f, -0.72f, 0.44f, -1.51f, 0.44f, -2.33f)
                curveToRelative(0.0f, -0.82f, -0.16f, -1.59f, -0.43f, -2.31f)
                lineToRelative(-2.33f, 2.32f)
                close()
                moveTo(19.53f, 6.71f)
                lineToRelative(-1.26f, 1.26f)
                curveToRelative(0.63f, 1.21f, 0.98f, 2.57f, 0.98f, 4.02f)
                reflectiveCurveToRelative(-0.36f, 2.82f, -0.98f, 4.02f)
                lineToRelative(1.2f, 1.2f)
                curveToRelative(0.97f, -1.54f, 1.54f, -3.36f, 1.54f, -5.31f)
                curveToRelative(-0.01f, -1.89f, -0.55f, -3.67f, -1.48f, -5.19f)
                close()
                moveTo(15.71f, 7.71f)
                lineTo(10.0f, 2.0f)
                lineTo(9.0f, 2.0f)
                verticalLineToRelative(7.59f)
                lineTo(4.41f, 5.0f)
                lineTo(3.0f, 6.41f)
                lineTo(8.59f, 12.0f)
                lineTo(3.0f, 17.59f)
                lineTo(4.41f, 19.0f)
                lineTo(9.0f, 14.41f)
                lineTo(9.0f, 22.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(5.71f, -5.71f)
                lineToRelative(-4.3f, -4.29f)
                lineToRelative(4.3f, -4.29f)
                close()
                moveTo(11.0f, 5.83f)
                lineToRelative(1.88f, 1.88f)
                lineTo(11.0f, 9.59f)
                lineTo(11.0f, 5.83f)
                close()
                moveTo(12.88f, 16.29f)
                lineTo(11.0f, 18.17f)
                verticalLineToRelative(-3.76f)
                lineToRelative(1.88f, 1.88f)
                close()
            }
        }
    }

    val Videocam: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Videocam") {
            materialPath {
                moveTo(17.0f, 10.5f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(10.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-3.5f)
                lineToRelative(4.0f, 4.0f)
                verticalLineToRelative(-11.0f)
                lineToRelative(-4.0f, 4.0f)
                close()
            }
        }
    }

    val CellTower: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.CellTower") {
            materialPath {
                moveTo(7.3f, 14.7f)
                lineToRelative(1.2f, -1.2f)
                curveToRelative(-1.0f, -1.0f, -1.5f, -2.3f, -1.5f, -3.5f)
                curveToRelative(0.0f, -1.3f, 0.5f, -2.6f, 1.5f, -3.5f)
                lineTo(7.3f, 5.3f)
                curveToRelative(-1.3f, 1.3f, -2.0f, 3.0f, -2.0f, 4.7f)
                reflectiveCurveTo(6.0f, 13.4f, 7.3f, 14.7f)
                close()
            }
            materialPath {
                moveTo(19.1f, 2.9f)
                lineToRelative(-1.2f, 1.2f)
                curveToRelative(1.6f, 1.6f, 2.4f, 3.8f, 2.4f, 5.9f)
                curveToRelative(0.0f, 2.1f, -0.8f, 4.3f, -2.4f, 5.9f)
                lineToRelative(1.2f, 1.2f)
                curveToRelative(2.0f, -2.0f, 2.9f, -4.5f, 2.9f, -7.1f)
                curveTo(22.0f, 7.4f, 21.0f, 4.9f, 19.1f, 2.9f)
                close()
            }
            materialPath {
                moveTo(6.1f, 4.1f)
                lineTo(4.9f, 2.9f)
                curveTo(3.0f, 4.9f, 2.0f, 7.4f, 2.0f, 10.0f)
                curveToRelative(0.0f, 2.6f, 1.0f, 5.1f, 2.9f, 7.1f)
                lineToRelative(1.2f, -1.2f)
                curveToRelative(-1.6f, -1.6f, -2.4f, -3.8f, -2.4f, -5.9f)
                curveTo(3.7f, 7.9f, 4.5f, 5.7f, 6.1f, 4.1f)
                close()
            }
            materialPath {
                moveTo(16.7f, 14.7f)
                curveToRelative(1.3f, -1.3f, 2.0f, -3.0f, 2.0f, -4.7f)
                curveToRelative(-0.1f, -1.7f, -0.7f, -3.4f, -2.0f, -4.7f)
                lineToRelative(-1.2f, 1.2f)
                curveToRelative(1.0f, 1.0f, 1.5f, 2.3f, 1.5f, 3.5f)
                curveToRelative(0.0f, 1.3f, -0.5f, 2.6f, -1.5f, 3.5f)
                lineTo(16.7f, 14.7f)
                close()
            }
            materialPath {
                moveTo(14.5f, 10.0f)
                curveToRelative(0.0f, -1.38f, -1.12f, -2.5f, -2.5f, -2.5f)
                reflectiveCurveTo(9.5f, 8.62f, 9.5f, 10.0f)
                curveToRelative(0.0f, 0.76f, 0.34f, 1.42f, 0.87f, 1.88f)
                lineTo(7.0f, 22.0f)
                horizontalLineToRelative(2.0f)
                lineToRelative(0.67f, -2.0f)
                horizontalLineToRelative(4.67f)
                lineTo(15.0f, 22.0f)
                horizontalLineToRelative(2.0f)
                lineToRelative(-3.37f, -10.12f)
                curveTo(14.16f, 11.42f, 14.5f, 10.76f, 14.5f, 10.0f)
                close()
                moveTo(10.33f, 18.0f)
                lineTo(12.0f, 13.0f)
                lineToRelative(1.67f, 5.0f)
                horizontalLineTo(10.33f)
                close()
            }
        }
    }

    val Wifi: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Wifi") {
            materialPath {
                moveTo(1.0f, 9.0f)
                lineToRelative(2.0f, 2.0f)
                curveToRelative(4.97f, -4.97f, 13.03f, -4.97f, 18.0f, 0.0f)
                lineToRelative(2.0f, -2.0f)
                curveTo(16.93f, 2.93f, 7.08f, 2.93f, 1.0f, 9.0f)
                close()
                moveTo(9.0f, 17.0f)
                lineToRelative(3.0f, 3.0f)
                lineToRelative(3.0f, -3.0f)
                curveToRelative(-1.65f, -1.66f, -4.34f, -1.66f, -6.0f, 0.0f)
                close()
                moveTo(5.0f, 13.0f)
                lineToRelative(2.0f, 2.0f)
                curveToRelative(2.76f, -2.76f, 7.24f, -2.76f, 10.0f, 0.0f)
                lineToRelative(2.0f, -2.0f)
                curveTo(15.14f, 9.14f, 8.87f, 9.14f, 5.0f, 13.0f)
                close()
            }
        }
    }

    val Nfc: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Nfc") {
            materialPath {
                moveTo(20.0f, 2.0f)
                lineTo(4.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 20.0f)
                lineTo(4.0f, 20.0f)
                lineTo(4.0f, 4.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(16.0f)
                close()
                moveTo(18.0f, 6.0f)
                horizontalLineToRelative(-5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(2.28f)
                curveToRelative(-0.6f, 0.35f, -1.0f, 0.98f, -1.0f, 1.72f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                curveToRelative(0.0f, -0.74f, -0.4f, -1.38f, -1.0f, -1.72f)
                lineTo(13.0f, 8.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(8.0f)
                lineTo(8.0f, 16.0f)
                lineTo(8.0f, 8.0f)
                horizontalLineToRelative(2.0f)
                lineTo(10.0f, 6.0f)
                lineTo(6.0f, 6.0f)
                verticalLineToRelative(12.0f)
                horizontalLineToRelative(12.0f)
                lineTo(18.0f, 6.0f)
                close()
            }
        }
    }

    val PhoneAndroid: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.PhoneAndroid") {
            materialPath {
                moveTo(16.0f, 1.0f)
                lineTo(8.0f, 1.0f)
                curveTo(6.34f, 1.0f, 5.0f, 2.34f, 5.0f, 4.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
                lineTo(19.0f, 4.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                close()
                moveTo(14.0f, 21.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(17.25f, 18.0f)
                lineTo(6.75f, 18.0f)
                lineTo(6.75f, 4.0f)
                horizontalLineToRelative(10.5f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
    }

    val Policy: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Policy") {
            materialPath {
                moveTo(21.0f, 5.0f)
                lineToRelative(-9.0f, -4.0f)
                lineTo(3.0f, 5.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                curveToRelative(2.3f, -0.56f, 4.33f, -1.9f, 5.88f, -3.71f)
                lineToRelative(-3.12f, -3.12f)
                curveToRelative(-1.94f, 1.29f, -4.58f, 1.07f, -6.29f, -0.64f)
                curveToRelative(-1.95f, -1.95f, -1.95f, -5.12f, 0.0f, -7.07f)
                curveToRelative(1.95f, -1.95f, 5.12f, -1.95f, 7.07f, 0.0f)
                curveToRelative(1.71f, 1.71f, 1.92f, 4.35f, 0.64f, 6.29f)
                lineToRelative(2.9f, 2.9f)
                curveTo(20.29f, 15.69f, 21.0f, 13.38f, 21.0f, 11.0f)
                verticalLineTo(5.0f)
                close()
            }
            materialPath {
                moveTo(12.0f, 12.0f)
                moveToRelative(-3.0f, 0.0f)
                arcToRelative(3.0f, 3.0f, 0.0f, true, true, 6.0f, 0.0f)
                arcToRelative(3.0f, 3.0f, 0.0f, true, true, -6.0f, 0.0f)
            }
        }
    }

    val Shield: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Shield") {
            materialPath {
                moveTo(12.0f, 1.0f)
                lineTo(3.0f, 5.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                verticalLineTo(5.0f)
                lineToRelative(-9.0f, -4.0f)
                close()
            }
        }
    }

    val Forum: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Forum") {
            materialPath {
                moveTo(21.0f, 6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(9.0f)
                lineTo(6.0f, 15.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(11.0f)
                lineToRelative(4.0f, 4.0f)
                lineTo(22.0f, 7.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(17.0f, 12.0f)
                lineTo(17.0f, 3.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(3.0f, 2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(14.0f)
                lineToRelative(4.0f, -4.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                close()
            }
        }
    }

    val PhoneInTalk: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.PhoneInTalk") {
            materialPath {
                moveTo(20.0f, 15.5f)
                curveToRelative(-1.25f, 0.0f, -2.45f, -0.2f, -3.57f, -0.57f)
                curveToRelative(-0.35f, -0.11f, -0.74f, -0.03f, -1.02f, 0.24f)
                lineToRelative(-2.2f, 2.2f)
                curveToRelative(-2.83f, -1.44f, -5.15f, -3.75f, -6.59f, -6.59f)
                lineToRelative(2.2f, -2.21f)
                curveToRelative(0.28f, -0.26f, 0.36f, -0.65f, 0.25f, -1.0f)
                curveTo(8.7f, 6.45f, 8.5f, 5.25f, 8.5f, 4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                curveToRelative(0.0f, 9.39f, 7.61f, 17.0f, 17.0f, 17.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-3.5f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(19.0f, 12.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, -4.97f, -4.03f, -9.0f, -9.0f, -9.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(3.87f, 0.0f, 7.0f, 3.13f, 7.0f, 7.0f)
                close()
                moveTo(15.0f, 12.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
                close()
            }
        }
    }

    val DirectionsCar: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.DirectionsCar") {
            materialPath {
                moveTo(18.92f, 6.01f)
                curveTo(18.72f, 5.42f, 18.16f, 5.0f, 17.5f, 5.0f)
                horizontalLineToRelative(-11.0f)
                curveToRelative(-0.66f, 0.0f, -1.21f, 0.42f, -1.42f, 1.01f)
                lineTo(3.0f, 12.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-8.0f)
                lineToRelative(-2.08f, -5.99f)
                close()
                moveTo(6.5f, 16.0f)
                curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveTo(5.67f, 13.0f, 6.5f, 13.0f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(7.33f, 16.0f, 6.5f, 16.0f)
                close()
                moveTo(17.5f, 16.0f)
                curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                close()
                moveTo(5.0f, 11.0f)
                lineToRelative(1.5f, -4.5f)
                horizontalLineToRelative(11.0f)
                lineTo(19.0f, 11.0f)
                lineTo(5.0f, 11.0f)
                close()
            }
        }
    }

    val Navigation: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Navigation") {
            materialPath {
                moveTo(12.0f, 2.0f)
                lineTo(4.5f, 20.29f)
                lineToRelative(0.71f, 0.71f)
                lineTo(12.0f, 18.0f)
                lineToRelative(6.79f, 3.0f)
                lineToRelative(0.71f, -0.71f)
                close()
            }
        }
    }

    val Radio: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Radio") {
            materialPath {
                moveTo(3.24f, 6.15f)
                curveTo(2.51f, 6.43f, 2.0f, 7.17f, 2.0f, 8.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.11f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 8.0f)
                curveToRelative(0.0f, -1.11f, -0.89f, -2.0f, -2.0f, -2.0f)
                lineTo(8.3f, 6.0f)
                lineToRelative(8.26f, -3.34f)
                lineTo(15.88f, 1.0f)
                lineTo(3.24f, 6.15f)
                close()
                moveTo(7.0f, 20.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
                reflectiveCurveToRelative(1.34f, -3.0f, 3.0f, -3.0f)
                reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
                reflectiveCurveToRelative(-1.34f, 3.0f, -3.0f, 3.0f)
                close()
                moveTo(20.0f, 12.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 12.0f)
                lineTo(4.0f, 8.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(4.0f)
                close()
            }
        }
    }

    val Speed: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Speed") {
            materialPath {
                moveTo(20.38f, 8.57f)
                lineToRelative(-1.23f, 1.85f)
                arcToRelative(8.0f, 8.0f, 0.0f, false, true, -0.22f, 7.58f)
                lineTo(5.07f, 18.0f)
                arcTo(8.0f, 8.0f, 0.0f, false, true, 15.58f, 6.85f)
                lineToRelative(1.85f, -1.23f)
                arcTo(10.0f, 10.0f, 0.0f, false, false, 3.35f, 19.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.72f, 1.0f)
                horizontalLineToRelative(13.85f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.74f, -1.0f)
                arcToRelative(10.0f, 10.0f, 0.0f, false, false, -0.27f, -10.44f)
                close()
                moveTo(10.59f, 15.41f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.83f, 0.0f)
                lineToRelative(5.66f, -8.49f)
                lineToRelative(-8.49f, 5.66f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, 2.83f)
                close()
            }
        }
    }

    val History: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.History") {
            materialPath {
                moveTo(13.0f, 3.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                lineTo(1.0f, 12.0f)
                lineToRelative(3.89f, 3.89f)
                lineToRelative(0.07f, 0.14f)
                lineTo(9.0f, 12.0f)
                lineTo(6.0f, 12.0f)
                curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
                reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
                curveToRelative(-1.93f, 0.0f, -3.68f, -0.79f, -4.94f, -2.06f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(8.27f, 19.99f, 10.51f, 21.0f, 13.0f, 21.0f)
                curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
                reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
                close()
                moveTo(12.0f, 8.0f)
                verticalLineToRelative(5.0f)
                lineToRelative(4.28f, 2.54f)
                lineToRelative(0.72f, -1.21f)
                lineToRelative(-3.5f, -2.08f)
                lineTo(13.5f, 8.0f)
                lineTo(12.0f, 8.0f)
                close()
            }
        }
    }

    val Insights: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Insights") {
            materialPath {
                moveTo(21.0f, 8.0f)
                curveToRelative(-1.45f, 0.0f, -2.26f, 1.44f, -1.93f, 2.51f)
                lineToRelative(-3.55f, 3.56f)
                curveToRelative(-0.3f, -0.09f, -0.74f, -0.09f, -1.04f, 0.0f)
                lineToRelative(-2.55f, -2.55f)
                curveTo(12.27f, 10.45f, 11.46f, 9.0f, 10.0f, 9.0f)
                curveToRelative(-1.45f, 0.0f, -2.27f, 1.44f, -1.93f, 2.52f)
                lineToRelative(-4.56f, 4.55f)
                curveTo(2.44f, 15.74f, 1.0f, 16.55f, 1.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                curveToRelative(1.45f, 0.0f, 2.26f, -1.44f, 1.93f, -2.51f)
                lineToRelative(4.55f, -4.56f)
                curveToRelative(0.3f, 0.09f, 0.74f, 0.09f, 1.04f, 0.0f)
                lineToRelative(2.55f, 2.55f)
                curveTo(12.73f, 16.55f, 13.54f, 18.0f, 15.0f, 18.0f)
                curveToRelative(1.45f, 0.0f, 2.27f, -1.44f, 1.93f, -2.52f)
                lineToRelative(3.56f, -3.55f)
                curveTo(21.56f, 12.26f, 23.0f, 11.45f, 23.0f, 10.0f)
                curveTo(23.0f, 8.9f, 22.1f, 8.0f, 21.0f, 8.0f)
                close()
            }
            materialPath {
                moveTo(15.0f, 9.0f)
                lineToRelative(0.94f, -2.07f)
                lineToRelative(2.06f, -0.93f)
                lineToRelative(-2.06f, -0.93f)
                lineToRelative(-0.94f, -2.07f)
                lineToRelative(-0.92f, 2.07f)
                lineToRelative(-2.08f, 0.93f)
                lineToRelative(2.08f, 0.93f)
                close()
            }
            materialPath {
                moveTo(3.5f, 11.0f)
                lineToRelative(0.5f, -2.0f)
                lineToRelative(2.0f, -0.5f)
                lineToRelative(-2.0f, -0.5f)
                lineToRelative(-0.5f, -2.0f)
                lineToRelative(-0.5f, 2.0f)
                lineToRelative(-2.0f, 0.5f)
                lineToRelative(2.0f, 0.5f)
                close()
            }
        }
    }

    val Timeline: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Timeline") {
            materialPath {
                moveTo(23.0f, 8.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                curveToRelative(-0.18f, 0.0f, -0.35f, -0.02f, -0.51f, -0.07f)
                lineToRelative(-3.56f, 3.55f)
                curveTo(16.98f, 13.64f, 17.0f, 13.82f, 17.0f, 14.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                curveToRelative(0.0f, -0.18f, 0.02f, -0.36f, 0.07f, -0.52f)
                lineToRelative(-2.55f, -2.55f)
                curveTo(10.36f, 10.98f, 10.18f, 11.0f, 10.0f, 11.0f)
                reflectiveCurveToRelative(-0.36f, -0.02f, -0.52f, -0.07f)
                lineToRelative(-4.55f, 4.56f)
                curveTo(4.98f, 15.65f, 5.0f, 15.82f, 5.0f, 16.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                curveToRelative(0.18f, 0.0f, 0.35f, 0.02f, 0.51f, 0.07f)
                lineToRelative(4.56f, -4.55f)
                curveTo(8.02f, 9.36f, 8.0f, 9.18f, 8.0f, 9.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                curveToRelative(0.0f, 0.18f, -0.02f, 0.36f, -0.07f, 0.52f)
                lineToRelative(2.55f, 2.55f)
                curveTo(14.64f, 12.02f, 14.82f, 12.0f, 15.0f, 12.0f)
                reflectiveCurveToRelative(0.36f, 0.02f, 0.52f, 0.07f)
                lineToRelative(3.55f, -3.56f)
                curveTo(19.02f, 8.35f, 19.0f, 8.18f, 19.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveTo(23.0f, 6.9f, 23.0f, 8.0f)
                close()
            }
        }
    }

    val Map: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Map") {
            materialPath {
                moveTo(20.5f, 3.0f)
                lineToRelative(-0.16f, 0.03f)
                lineTo(15.0f, 5.1f)
                lineTo(9.0f, 3.0f)
                lineTo(3.36f, 4.9f)
                curveToRelative(-0.21f, 0.07f, -0.36f, 0.25f, -0.36f, 0.48f)
                verticalLineTo(20.5f)
                curveToRelative(0.0f, 0.28f, 0.22f, 0.5f, 0.5f, 0.5f)
                lineToRelative(0.16f, -0.03f)
                lineTo(9.0f, 18.9f)
                lineToRelative(6.0f, 2.1f)
                lineToRelative(5.64f, -1.9f)
                curveToRelative(0.21f, -0.07f, 0.36f, -0.25f, 0.36f, -0.48f)
                verticalLineTo(3.5f)
                curveToRelative(0.0f, -0.28f, -0.22f, -0.5f, -0.5f, -0.5f)
                close()
                moveTo(15.0f, 19.0f)
                lineToRelative(-6.0f, -2.11f)
                verticalLineTo(5.0f)
                lineToRelative(6.0f, 2.11f)
                verticalLineTo(19.0f)
                close()
            }
        }
    }

    val TrackChanges: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.TrackChanges") {
            materialPath {
                moveTo(19.07f, 4.93f)
                lineToRelative(-1.41f, 1.41f)
                curveTo(19.1f, 7.79f, 20.0f, 9.79f, 20.0f, 12.0f)
                curveToRelative(0.0f, 4.42f, -3.58f, 8.0f, -8.0f, 8.0f)
                reflectiveCurveToRelative(-8.0f, -3.58f, -8.0f, -8.0f)
                curveToRelative(0.0f, -4.08f, 3.05f, -7.44f, 7.0f, -7.93f)
                verticalLineToRelative(2.02f)
                curveTo(8.16f, 6.57f, 6.0f, 9.03f, 6.0f, 12.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                reflectiveCurveToRelative(6.0f, -2.69f, 6.0f, -6.0f)
                curveToRelative(0.0f, -1.66f, -0.67f, -3.16f, -1.76f, -4.24f)
                lineToRelative(-1.41f, 1.41f)
                curveTo(15.55f, 9.9f, 16.0f, 10.9f, 16.0f, 12.0f)
                curveToRelative(0.0f, 2.21f, -1.79f, 4.0f, -4.0f, 4.0f)
                reflectiveCurveToRelative(-4.0f, -1.79f, -4.0f, -4.0f)
                curveToRelative(0.0f, -1.86f, 1.28f, -3.41f, 3.0f, -3.86f)
                verticalLineToRelative(2.14f)
                curveToRelative(-0.6f, 0.35f, -1.0f, 0.98f, -1.0f, 1.72f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                curveToRelative(0.0f, -0.74f, -0.4f, -1.38f, -1.0f, -1.72f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(-1.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                curveToRelative(0.0f, -2.76f, -1.12f, -5.26f, -2.93f, -7.07f)
                close()
            }
        }
    }

    val LockOpen: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.LockOpen") {
            materialPath {
                moveTo(12.0f, 17.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(18.0f, 8.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(17.0f, 6.0f)
                curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                reflectiveCurveTo(7.0f, 3.24f, 7.0f, 6.0f)
                horizontalLineToRelative(1.9f)
                curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
                verticalLineToRelative(2.0f)
                lineTo(6.0f, 8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(10.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(20.0f, 10.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(18.0f, 20.0f)
                lineTo(6.0f, 20.0f)
                lineTo(6.0f, 10.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(10.0f)
                close()
            }
        }
    }

    val LightMode: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.LightMode") {
            materialPath {
                moveTo(12.0f, 7.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
                reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
                reflectiveCurveTo(14.76f, 7.0f, 12.0f, 7.0f)
                lineTo(12.0f, 7.0f)
                close()
                moveTo(2.0f, 13.0f)
                lineToRelative(2.0f, 0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineToRelative(-2.0f, 0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveTo(1.45f, 13.0f, 2.0f, 13.0f)
                close()
                moveTo(20.0f, 13.0f)
                lineToRelative(2.0f, 0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineToRelative(-2.0f, 0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveTo(19.45f, 13.0f, 20.0f, 13.0f)
                close()
                moveTo(11.0f, 2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(2.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveTo(11.0f, 1.45f, 11.0f, 2.0f)
                close()
                moveTo(11.0f, 20.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                curveTo(11.45f, 19.0f, 11.0f, 19.45f, 11.0f, 20.0f)
                close()
                moveTo(5.99f, 4.58f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0.0f)
                reflectiveCurveToRelative(0.39f, -1.03f, 0.0f, -1.41f)
                lineTo(5.99f, 4.58f)
                close()
                moveTo(18.36f, 16.95f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0.0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                lineTo(18.36f, 16.95f)
                close()
                moveTo(19.42f, 5.99f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                lineToRelative(-1.06f, 1.06f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0.0f)
                lineTo(19.42f, 5.99f)
                close()
                moveTo(7.05f, 18.36f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                lineToRelative(-1.06f, 1.06f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0.0f)
                lineTo(7.05f, 18.36f)
                close()
            }
        }
    }

    val DarkMode: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.DarkMode") {
            materialPath {
                moveTo(12.0f, 3.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                reflectiveCurveToRelative(4.03f, 9.0f, 9.0f, 9.0f)
                reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
                curveToRelative(0.0f, -0.46f, -0.04f, -0.92f, -0.1f, -1.36f)
                curveToRelative(-0.98f, 1.37f, -2.58f, 2.26f, -4.4f, 2.26f)
                curveToRelative(-2.98f, 0.0f, -5.4f, -2.42f, -5.4f, -5.4f)
                curveToRelative(0.0f, -1.81f, 0.89f, -3.42f, 2.26f, -4.4f)
                curveTo(12.92f, 3.04f, 12.46f, 3.0f, 12.0f, 3.0f)
                lineTo(12.0f, 3.0f)
                close()
            }
        }
    }

    val Remove: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Remove") {
            materialPath {
                moveTo(19.0f, 13.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
    }

    val DoneAll: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.DoneAll") {
            materialPath {
                moveTo(18.0f, 7.0f)
                lineToRelative(-1.41f, -1.41f)
                lineToRelative(-6.34f, 6.34f)
                lineToRelative(1.41f, 1.41f)
                lineTo(18.0f, 7.0f)
                close()
                moveTo(22.24f, 5.59f)
                lineTo(11.66f, 16.17f)
                lineTo(7.48f, 12.0f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(11.66f, 19.0f)
                lineToRelative(12.0f, -12.0f)
                lineToRelative(-1.42f, -1.41f)
                close()
                moveTo(0.41f, 13.41f)
                lineTo(6.0f, 19.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(1.83f, 12.0f)
                lineTo(0.41f, 13.41f)
                close()
            }
        }
    }

    val Backspace: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        materialIcon(name = "Clairix.Backspace", autoMirror = true) {
            materialPath {
                moveTo(22.0f, 3.0f)
                lineTo(7.0f, 3.0f)
                curveToRelative(-0.69f, 0.0f, -1.23f, 0.35f, -1.59f, 0.88f)
                lineTo(0.0f, 12.0f)
                lineToRelative(5.41f, 8.11f)
                curveToRelative(0.36f, 0.53f, 0.9f, 0.89f, 1.59f, 0.89f)
                horizontalLineToRelative(15.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(24.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(19.0f, 15.59f)
                lineTo(17.59f, 17.0f)
                lineTo(14.0f, 13.41f)
                lineTo(10.41f, 17.0f)
                lineTo(9.0f, 15.59f)
                lineTo(12.59f, 12.0f)
                lineTo(9.0f, 8.41f)
                lineTo(10.41f, 7.0f)
                lineTo(14.0f, 10.59f)
                lineTo(17.59f, 7.0f)
                lineTo(19.0f, 8.41f)
                lineTo(15.41f, 12.0f)
                lineTo(19.0f, 15.59f)
                close()
            }
        }
    }
}
