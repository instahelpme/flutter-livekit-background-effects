// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "livekit_background_effects_native",
    platforms: [
        .iOS("15.0")
    ],
    products: [
        .library(name: "livekit-background-effects-native", targets: ["livekit_background_effects_native"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        .package(url: "https://github.com/flutter-webrtc/flutter-webrtc", from: "1.5.0")
    ],
    targets: [
        .target(
            name: "livekit_background_effects_native",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "flutter-webrtc", package: "flutter-webrtc"),
                .product(name: "WebRTC", package: "flutter-webrtc")
            ],
            resources: [
                .process("PrivacyInfo.xcprivacy")
            ]
        )
    ]
)
