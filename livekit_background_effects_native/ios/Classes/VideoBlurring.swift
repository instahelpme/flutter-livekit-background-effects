//
//  VideoBlurring.swift
//  Pods
//
//  Created by Tobias Ollmann on 08.07.25.
//

import CoreImage
import Foundation
import Vision
import WebRTC
import flutter_webrtc

@available(iOS 15.0, *)
private var segmentationRequest = VNGeneratePersonSegmentationRequest()

private typealias BlurOptions = (Int?, CIImage?)

@objc public class VideoBlurring: NSObject, ExternalVideoProcessingDelegate {
  private var sink: (any RTCVideoCapturerDelegate)? = nil
  private var lock: NSLock = NSLock()
  private var inFlight: RTCVideoFrame?
  private var inFlightCapturer: RTCVideoCapturer?
  private var next: RTCVideoFrame?
  private var nextCapturer: RTCVideoCapturer?
  private var bgLock: NSLock = NSLock()
  private var virtualBG: CIImage?
  private var blurRadius: Int?
  private var scaledVirtualBG: CIImage?
  private var virtualBGRotation: RTCVideoRotation?

  private let requestHandler = VNSequenceRequestHandler()

  override init() {
    super.init()
    if #available(iOS 15.0, *) {
      segmentationRequest.qualityLevel = .balanced
      segmentationRequest.outputPixelFormat = kCVPixelFormatType_OneComponent8
    }
  }

  public func setSink(_ sink: any RTCVideoCapturerDelegate) {
    self.sink = sink
  }

  public func capturer(
    _ capturer: RTCVideoCapturer,
    didCapture frame: RTCVideoFrame
  ) {
    let blur = bgLock.withLock({ () -> BlurOptions in
      getBlurOptions(
        forWidth: Int(frame.width),
        andHeight: Int(frame.height),
        andRotation: frame.rotation
      )
    })
    let run = lock.withLock({ () -> Bool in
      if inFlight == nil {
        inFlight = frame
        inFlightCapturer = capturer
        next = nil
        nextCapturer = nil
        return true
      } else {
        next = frame
        nextCapturer = capturer
        return false
      }
    })
    if run {
      processFrame(inFlight!, capturer: inFlightCapturer!, options: blur)
    }
  }

  public func setBg(_ virtualBackground: UIImage?, and radius: Int?) {
    var ciimage: CIImage?
    if virtualBackground != nil {
      ciimage = CIImage(image: virtualBackground!)
    }
    bgLock.withLock({ () in
      blurRadius = radius
      virtualBG = ciimage
      scaledVirtualBG = nil
    })
  }

  private func processFrame(
    _ frame: RTCVideoFrame,
    capturer: RTCVideoCapturer,
    options: BlurOptions
  ) {
    if options.0 == nil && options.1 == nil {
      sink?.capturer(capturer, didCapture: frame)
      finalizeFrame()
      return
    }

    if #available(iOS 15.0, *) {
      guard let pixelBuffer = videoFrameToPixelBuffer(frame) else {
        print("Failed to convert video frame to pixel buffer")
        return
      }

      applyBackground(
        from: frame,
        to: pixelBuffer,
        capturer: capturer,
        options: options
      )
    }

  }

  @available(iOS 15.0, *)
  private func applyBackground(
    from frame: RTCVideoFrame,
    to pixelBuffer: CVPixelBuffer,
    capturer: RTCVideoCapturer,
    options: BlurOptions,
  ) {

    try? requestHandler.perform([segmentationRequest], on: pixelBuffer)

    guard let maskBuffer = segmentationRequest.results?.first?.pixelBuffer
    else {
      finalizeFrame()
      return
    }

    var blended: CIImage
    if options.0 != nil {
      blended = blurImage(pixelBuffer, with: maskBuffer, andRadius: options.0!)
    } else {
      blended = applyVirtualBackground(
        pixelBuffer,
        with: maskBuffer,
        andBG: options.1!
      )
    }

    guard
      let processedFrame = ciImageToVideoFrame(
        blended,
        rotation: frame.rotation,
        timeStampNs: frame.timeStampNs
      )
    else {
      print("Failed to convert pixel buffer to video frame")
      return
    }
    sink?.capturer(capturer, didCapture: processedFrame)
    finalizeFrame()
  }

  private func blurImage(
    _ original: CVPixelBuffer,
    with mask: CVPixelBuffer,
    andRadius radius: Int
  )
    -> CIImage
  {
    let originalImage = CIImage(cvPixelBuffer: original)

    var maskImage = CIImage(cvImageBuffer: mask)
    let maskInvertFilter = CIFilter(name: "CIColorInvert")!
    maskInvertFilter.setValue(maskImage, forKey: kCIInputImageKey)
    maskImage = maskInvertFilter.outputImage!

    // Scale the mask image to fit the bounds of the video frame.
    let scaleX = originalImage.extent.width / maskImage.extent.width
    let scaleY = originalImage.extent.height / maskImage.extent.height
    let scaledMaskImage = maskImage.transformed(
      by: .init(scaleX: scaleX, y: scaleY)
    )

    let blendFilter = CIFilter(name: "CIMaskedVariableBlur")!
    blendFilter.setValue(originalImage, forKey: kCIInputImageKey)
    blendFilter.setValue(scaledMaskImage, forKey: "inputMask")
    blendFilter.setValue(radius, forKey: kCIInputRadiusKey)

    let blended = blendFilter.outputImage!

    //   return blendFilter.outputImage!
    return blended.cropped(to: originalImage.extent)
  }

  private func applyVirtualBackground(
    _ original: CVPixelBuffer,
    with mask: CVPixelBuffer,
    andBG bg: CIImage
  )
    -> CIImage
  {
    let originalImage = CIImage(cvPixelBuffer: original)

    let maskImage = CIImage(cvImageBuffer: mask)

    // Scale the mask image to fit the bounds of the video frame.
    let scaleX = originalImage.extent.width / maskImage.extent.width
    let scaleY = originalImage.extent.height / maskImage.extent.height
    let scaledMaskImage = maskImage.transformed(
      by: .init(scaleX: scaleX, y: scaleY)
    )

    let blendFilter = CIFilter(name: "CIBlendWithMask")!
    blendFilter.setValue(originalImage, forKey: kCIInputImageKey)
    blendFilter.setValue(scaledMaskImage, forKey: kCIInputMaskImageKey)
    blendFilter.setValue(bg, forKey: kCIInputBackgroundImageKey)

    let blended = blendFilter.outputImage!

    //   return blendFilter.outputImage!
    return blended  //.cropped(to: originalImage.extent)

  }

  private func finalizeFrame() {
    let run = lock.withLock({ () -> Bool in
      inFlight = nil
      inFlightCapturer = nil
      return false

      if next == nil {
        inFlight = nil
        inFlightCapturer = nil
        return false
      } else {
        inFlight = next
        inFlightCapturer = nextCapturer
        next = nil
        nextCapturer = nil
        return true
      }
    })
    if run {
      // tail recursion optimization for the rescue..
      let blur = getBlurOptions(
        forWidth: Int(inFlight!.width),
        andHeight: Int(inFlight!.height),
        andRotation: inFlight!.rotation
      )
      processFrame(inFlight!, capturer: inFlightCapturer!, options: blur)
    }
  }

  private func pixelBufferToVideoFrame(
    _ pixelBuffer: CVPixelBuffer,
    rotation: RTCVideoRotation,
    timeStampNs: Int64
  ) -> RTCVideoFrame? {

    let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
    return ciImageToVideoFrame(
      ciImage,
      rotation: rotation,
      timeStampNs: timeStampNs
    )
  }

  private func ciImageToVideoFrame(
    _ ciImage: CIImage,
    rotation: RTCVideoRotation,
    timeStampNs: Int64
  ) -> RTCVideoFrame? {
    guard
      let formattedBuffer = ciImageToPixelBuffer(
        ciImage,
        pixelFormat: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
      )
    else {
      return nil
    }

    let rtcPixelBuffer = RTCCVPixelBuffer(pixelBuffer: formattedBuffer)
    return RTCVideoFrame(
      buffer: rtcPixelBuffer,
      rotation: rotation,
      timeStampNs: timeStampNs
    )

  }

  private func videoFrameToPixelBuffer(_ rtcVideoFrame: RTCVideoFrame)
    -> CVPixelBuffer?
  {
    guard let cvPixelBuffer = rtcVideoFrame.buffer as? RTCCVPixelBuffer else {
      print("Error: RTCVideoFrame is not of type RTCCVPixelBuffer")
      return nil
    }

    let pixelBuffer = cvPixelBuffer.pixelBuffer
    let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
    //    let targetOrientation: CGImagePropertyOrientation =
    //    switch (rtcVideoFrame.rotation) {
    //
    //    case ._0:
    //      CGImagePropertyOrientation.up
    //    case ._90:
    //      CGImagePropertyOrientation.right
    //    case ._180:
    //      CGImagePropertyOrientation.down
    //    case ._270:
    //      CGImagePropertyOrientation.left
    //    @unknown default:
    //      CGImagePropertyOrientation.up
    //    }
    //    let rotatedCiImage = ciImage.oriented(targetOrientation)
    //    print("frame orientation \(rtcVideoFrame.rotation)")
    //    print("ciImage \(ciImage.extent)")
    //    print("rotatedCiImage \(rotatedCiImage.extent)")
    return ciImageToPixelBuffer(ciImage)
  }

  private func ciImageToPixelBuffer(
    _ ciImage: CIImage,
    pixelFormat: OSType = kCVPixelFormatType_32BGRA
  ) -> CVPixelBuffer? {
    let options: [CIContextOption: Any] = [
      .useSoftwareRenderer: false
    ]

    let ciContext = CIContext(options: options)

    let width = Int(ciImage.extent.width)
    let height = Int(ciImage.extent.height)

    let pixelBufferAttributes: [String: Any] = [
      //      kCVPixelBufferCGImageCompatibilityKey as String: true,
      //      kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
      //      kCVPixelBufferMetalCompatibilityKey as String: true,
      //      kCVPixelBufferWidthKey as String: width,
      //      kCVPixelBufferHeightKey as String: height,
      kCVPixelBufferPixelFormatTypeKey as String: pixelFormat
    ]

    var pixelBuffer: CVPixelBuffer?
    let status = CVPixelBufferCreate(
      kCFAllocatorDefault,
      width,
      height,
      pixelFormat,
      pixelBufferAttributes as CFDictionary,
      &pixelBuffer
    )

    guard status == kCVReturnSuccess, let result = pixelBuffer else {
      return nil
    }
    ciContext.render(ciImage, to: result)

    return result
  }

  private func getBlurOptions(
    forWidth width: Int,
    andHeight height: Int,
    andRotation rotation: RTCVideoRotation
  ) -> BlurOptions {
    if blurRadius != nil {
      return (Int(blurRadius!), nil)
    }
    if virtualBG == nil {
      return (nil, nil)
    }

    if scaledVirtualBG != nil {
      let scaledBG: CIImage = scaledVirtualBG!
      if scaledBG.extent.size.equalTo(CGSize(width: width, height: height)) && rotation == virtualBGRotation {
        return (nil, scaledVirtualBG)
      }
    }

    let s1 = virtualBG!.extent.size
    let vBG: CIImage
    switch rotation {
    case ._0:
      vBG = virtualBG!
    case ._90:
      vBG = virtualBG!.transformed(by: CGAffineTransform(rotationAngle: 90 * .pi/180).translatedBy(x: 0, y: -s1.height))
    case ._180:
      vBG = virtualBG!.transformed(by: CGAffineTransform(rotationAngle: 180 * .pi/180).translatedBy(x: -s1.width, y: -s1.height))
    case ._270:
      vBG = virtualBG!.transformed(by: CGAffineTransform(rotationAngle: 270 * .pi/180).translatedBy(x: -s1.width, y: 0))
    @unknown default:
      return (nil, nil)
    }
    let size = vBG.extent.size
    let scaleWidth = Double(width) / size.width
    let scaleHeight = Double(height) / size.height

    //let minScale = Double.minimum(scaleHeight, scaleWidth)
    let maxScale = Double.maximum(scaleHeight, scaleWidth)

    let transformation = CGAffineTransform(scaleX: maxScale, y: maxScale)  //.translatedBy(x: size.width * maxScale - Double(width), y: size.height * maxScale - Double(height))

    scaledVirtualBG = vBG.transformed(by: transformation).cropped(
      to: CGRect(x: 0, y: 0, width: width, height: height)
    )
    virtualBGRotation = rotation

    return (nil, scaledVirtualBG)
  }
}
