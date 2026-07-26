import AppKit
import Foundation

guard CommandLine.arguments.count == 2 else {
    fputs("usage: swift generate-macos-icon.swift <output.png>\n", stderr)
    exit(2)
}

let size = 1024
guard let bitmap = NSBitmapImageRep(
    bitmapDataPlanes: nil,
    pixelsWide: size,
    pixelsHigh: size,
    bitsPerSample: 8,
    samplesPerPixel: 4,
    hasAlpha: true,
    isPlanar: false,
    colorSpaceName: .deviceRGB,
    bytesPerRow: 0,
    bitsPerPixel: 0
) else {
    fatalError("unable to create bitmap")
}

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
NSColor.clear.setFill()
NSRect(x: 0, y: 0, width: size, height: size).fill()

let iconRect = NSRect(x: 72, y: 72, width: 880, height: 880)
let iconShape = NSBezierPath(roundedRect: iconRect, xRadius: 230, yRadius: 230)
iconShape.addClip()
let gradient = NSGradient(
    starting: NSColor(calibratedRed: 124 / 255, green: 131 / 255, blue: 1, alpha: 1),
    ending: NSColor(calibratedRed: 75 / 255, green: 80 / 255, blue: 201 / 255, alpha: 1)
)!
gradient.draw(in: iconRect, angle: -45)

NSGraphicsContext.restoreGraphicsState()
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)

let shadow = NSShadow()
shadow.shadowColor = NSColor(calibratedRed: 37 / 255, green: 42 / 255, blue: 131 / 255, alpha: 0.32)
shadow.shadowBlurRadius = 28
shadow.shadowOffset = NSSize(width: 0, height: -24)
shadow.set()

let wave = NSBezierPath()
wave.move(to: NSPoint(x: 236, y: 616))
wave.curve(
    to: NSPoint(x: 368, y: 392),
    controlPoint1: NSPoint(x: 298, y: 616),
    controlPoint2: NSPoint(x: 291, y: 392)
)
wave.curve(
    to: NSPoint(x: 512, y: 616),
    controlPoint1: NSPoint(x: 445, y: 392),
    controlPoint2: NSPoint(x: 438, y: 616)
)
wave.curve(
    to: NSPoint(x: 656, y: 392),
    controlPoint1: NSPoint(x: 586, y: 616),
    controlPoint2: NSPoint(x: 579, y: 392)
)
wave.curve(
    to: NSPoint(x: 788, y: 616),
    controlPoint1: NSPoint(x: 733, y: 392),
    controlPoint2: NSPoint(x: 726, y: 616)
)
wave.lineWidth = 86
wave.lineCapStyle = .round
wave.lineJoinStyle = .round
NSColor.white.setStroke()
wave.stroke()

NSGraphicsContext.restoreGraphicsState()

guard let png = bitmap.representation(using: .png, properties: [:]) else {
    fatalError("unable to encode PNG")
}
try png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]), options: .atomic)
