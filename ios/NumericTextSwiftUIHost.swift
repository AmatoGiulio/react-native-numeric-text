import SwiftUI
import UIKit

/**
 * The iOS renderer: SwiftUI's own `.contentTransition(.numericText())`, hosted in a UIView the
 * Fabric component view can own.
 *
 * There is nothing to reimplement on this platform — the transition the Android renderer spends
 * a thousand lines approximating is a single modifier here. So iOS does not share Android's
 * drawing code; it shares the *API*, and each side reaches the same behaviour the native way.
 *
 * Two consequences worth knowing:
 *
 * `animationDuration` is not honoured here. `.numericText()` is a spring, not a timed curve, and
 * SwiftUI exposes no duration for it; the reference this library is measured against uses
 * `.spring()` with its defaults, and so does this. The prop still scales Android's springs.
 *
 * The default face is `.system(design: .rounded)`, which is what Android's bundled Sunghyun Sans
 * stands in for. Pass `fontFamily: 'system'` for the plain system font, or any registered family
 * name to use it.
 */
@objc(NumericTextSwiftUIHost)
public final class NumericTextSwiftUIHost: UIView {
  private let model = NumericTextModel()
  private var hosting: UIHostingController<NumericTextRoot>?

  /// The value the last committed render drew, so `direction: 'automatic'` can tell which way the
  /// number moved. `nil` until the first render, which must not animate.
  private var lastValue: Double?

  /// The formatting props, exactly as `src/numberFormat.ts` resolved them, and the formatter they
  /// build. Kept together so the formatter is rebuilt only when something about it changed:
  /// `updateProps:` forwards every prop on every value change, and a `NumberFormatter` per frame
  /// of a press-and-hold is real work for no result.
  private var formatSpec = FormatSpec()
  private lazy var formatter = Self.makeFormatter(formatSpec)

  @objc public override init(frame: CGRect) {
    super.init(frame: frame)

    let controller = UIHostingController(rootView: NumericTextRoot(model: model))
    controller.view.backgroundColor = .clear
    controller.view.translatesAutoresizingMaskIntoConstraints = false
    if #available(iOS 16.0, *) {
      controller.sizingOptions = [.intrinsicContentSize]
    }

    addSubview(controller.view)
    NSLayoutConstraint.activate([
      controller.view.leadingAnchor.constraint(equalTo: leadingAnchor),
      controller.view.trailingAnchor.constraint(equalTo: trailingAnchor),
      controller.view.topAnchor.constraint(equalTo: topAnchor),
      controller.view.bottomAnchor.constraint(equalTo: bottomAnchor),
    ])
    hosting = controller
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("NumericTextSwiftUIHost is created in code only")
  }

  /**
   * The formatting props. Applied before the value, because the value is drawn through them.
   *
   * Separate from `apply` so neither selector grows past reading: between them they carry
   * everything the component exposes, and Objective-C spells every argument out.
   */
  // swiftlint:disable:next function_parameter_count
  @objc(applyFormatWithLocale:numberStyle:currency:currencyDisplay:currencySign:useGrouping:minimumIntegerDigits:minimumFractionDigits:maximumFractionDigits:minimumSignificantDigits:maximumSignificantDigits:trailingDecimalSeparator:)
  public func applyFormat(
    locale: String,
    numberStyle: String,
    currency: String,
    currencyDisplay: String,
    currencySign: String,
    useGrouping: Bool,
    minimumIntegerDigits: Int,
    minimumFractionDigits: Int,
    maximumFractionDigits: Int,
    minimumSignificantDigits: Int,
    maximumSignificantDigits: Int,
    trailingDecimalSeparator: Bool
  ) {
    let next = FormatSpec(
      locale: locale,
      numberStyle: numberStyle,
      currency: currency,
      currencyDisplay: currencyDisplay,
      currencySign: currencySign,
      useGrouping: useGrouping,
      minimumIntegerDigits: minimumIntegerDigits,
      minimumFractionDigits: minimumFractionDigits,
      maximumFractionDigits: maximumFractionDigits,
      minimumSignificantDigits: minimumSignificantDigits,
      maximumSignificantDigits: maximumSignificantDigits,
      trailingDecimalSeparator: trailingDecimalSeparator
    )
    guard next != formatSpec else { return }
    formatSpec = next
    formatter = Self.makeFormatter(next)
  }

  // swiftlint:disable:next function_parameter_count
  @objc(applyValue:direction:reduceMotion:fontSize:fontWeight:fontFamily:textColor:)
  public func apply(
    value: Double,
    direction: String,
    reduceMotion: String,
    fontSize: CGFloat,
    fontWeight: String,
    fontFamily: String?,
    textColor: UIColor?
  ) {
    model.text = Self.text(
      value,
      formatter: formatter,
      trailingDecimalSeparator: formatSpec.trailingDecimalSeparator
    )
    model.fontSize = fontSize > 0 ? fontSize : 48
    model.weight = Self.weight(from: fontWeight)
    model.fontFamily = fontFamily
    model.color = textColor.map(Color.init(uiColor:)) ?? .black

    model.countsDown = Self.countsDown(
      direction: direction,
      value: value,
      previous: lastValue
    )
    // The first render places the number; only later ones are transitions.
    model.animates = lastValue != nil && Self.animates(reduceMotion: reduceMotion)
    let changed = lastValue != value
    model.value = value
    lastValue = value

    #if DEBUG
    if model.animates, changed, let host = hosting?.view {
      NumericTextLayerProbe.shared.arm(
        root: host,
        label: model.text,
        countsDown: model.countsDown
      )
      NumericTextFrameRecorder.shared.arm(
        root: host,
        label: model.text,
        countsDown: model.countsDown
      )
    }
    #endif
  }

  // MARK: - Prop resolution

  /// Which way the digits roll. `automatic` follows the value, matching SwiftUI's own default.
  private static func countsDown(
    direction: String,
    value: Double,
    previous: Double?
  ) -> Bool {
    switch direction {
    case "up": return false
    case "down": return true
    default: return value < (previous ?? value)
    }
  }

  /**
   * Whether this change should animate.
   *
   * The prop names the reduce-motion *policy*, not the answer: `always` reduces motion always (so
   * never animates), `never` never reduces it, and `system` asks the accessibility setting.
   */
  private static func animates(reduceMotion: String) -> Bool {
    switch reduceMotion {
    case "always": return false
    case "never": return true
    default: return !UIAccessibility.isReduceMotionEnabled
    }
  }

  private static func weight(from fontWeight: String) -> Font.Weight {
    switch fontWeight {
    case "normal", "400": return .regular
    case "bold", "700": return .bold
    case "100": return .ultraLight
    case "200": return .thin
    case "300": return .light
    case "500": return .medium
    case "600": return .semibold
    case "800": return .heavy
    case "900": return .black
    default: return .regular
    }
  }

  // MARK: - Formatting

  /// The formatting props, as `src/numberFormat.ts` resolved them. A digit bound of -1 means the
  /// caller left it out and the style should supply its own.
  private struct FormatSpec: Equatable {
    var locale: String = "en-US"
    var numberStyle: String = "decimal"
    var currency: String = ""
    var currencyDisplay: String = "symbol"
    var currencySign: String = "standard"
    var useGrouping: Bool = true
    var minimumIntegerDigits: Int = -1
    var minimumFractionDigits: Int = -1
    var maximumFractionDigits: Int = -1
    var minimumSignificantDigits: Int = -1
    var maximumSignificantDigits: Int = -1
    var trailingDecimalSeparator: Bool = false
  }

  /**
   * The formatted number, with the decimal mark held after the last digit when the caller asked
   * for it and the format produced none.
   *
   * After the last *digit*, not at the end of the string: `de-DE` writes `1.234 €`, and a mark
   * appended blindly would land beyond the currency symbol.
   */
  private static func text(
    _ value: Double,
    formatter: NumberFormatter,
    trailingDecimalSeparator: Bool
  ) -> String {
    let formatted = formatter.string(from: NSNumber(value: value)) ?? String(value)
    guard trailingDecimalSeparator else { return formatted }

    // A currency format may use a different mark from a plain number in the same locale, so ask
    // the formatter for the one belonging to the style it is actually in.
    let money = formatter.numberStyle != .decimal && formatter.numberStyle != .percent
    let mark =
      (money ? formatter.currencyDecimalSeparator : formatter.decimalSeparator) ?? "."
    guard !formatted.contains(mark) else { return formatted }

    guard let lastDigit = formatted.lastIndex(where: { $0.isNumber }) else {
      return formatted + mark
    }
    let after = formatted.index(after: lastDigit)
    return String(formatted[..<after]) + mark + String(formatted[after...])
  }

  private static func makeFormatter(_ spec: FormatSpec) -> NumberFormatter {
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: spec.locale.isEmpty ? "en-US" : spec.locale)

    let money = spec.numberStyle == "currency" && !spec.currency.isEmpty
    formatter.numberStyle = style(spec, money: money)
    if money { formatter.currencyCode = spec.currency }
    formatter.usesGroupingSeparator = spec.useGrouping

    // Intl rounds halves away from zero; Foundation and ICU both default to half-even. Follow
    // Intl, so `2.5` at zero decimals reads as `3` on iOS, on Android, and on the web fallback
    // rather than as `3`, `2`, `2`.
    formatter.roundingMode = .halfUp

    if spec.minimumIntegerDigits >= 0 {
      formatter.minimumIntegerDigits = spec.minimumIntegerDigits
    }

    if spec.minimumSignificantDigits >= 0 || spec.maximumSignificantDigits >= 0 {
      let (low, high) = bounds(
        spec.minimumSignificantDigits,
        spec.maximumSignificantDigits,
        defaultMinimum: 1,
        defaultMaximum: 21
      )
      formatter.usesSignificantDigits = true
      formatter.maximumSignificantDigits = high
      formatter.minimumSignificantDigits = low
      return formatter
    }

    // Read before it is written: with the style and the code set, the formatter is already
    // carrying the currency's own fraction digits (2 for USD, 0 for JPY, 3 for BHD), and that is
    // exactly the default Intl would have applied.
    let percent = !money && spec.numberStyle == "percent"
    let defaultMaximum: Int
    if money {
      defaultMaximum = max(0, formatter.maximumFractionDigits)
    } else if percent {
      defaultMaximum = 0
    } else {
      defaultMaximum = 3
    }
    // A plain number is the only style that may drop a trailing zero, so it is the only one whose
    // minimum differs from its maximum.
    let defaultMinimum = (money || percent) ? defaultMaximum : 0

    let (low, high) = bounds(
      spec.minimumFractionDigits,
      spec.maximumFractionDigits,
      defaultMinimum: defaultMinimum,
      defaultMaximum: defaultMaximum
    )
    formatter.maximumFractionDigits = high
    formatter.minimumFractionDigits = low
    return formatter
  }

  private static func style(_ spec: FormatSpec, money: Bool) -> NumberFormatter.Style {
    guard money else {
      return spec.numberStyle == "percent" ? .percent : .decimal
    }
    switch spec.currencyDisplay {
    case "code": return .currencyISOCode
    case "name": return .currencyPlural
    // Accounting is a distinct CLDR pattern, so it is a style rather than a modifier, and it only
    // exists alongside the symbol. `code` and `name` above therefore win over it.
    default: return spec.currencySign == "accounting" ? .currencyAccounting : .currency
    }
  }

  /**
   * ECMA-402's rule for resolving digit bounds, so the three implementations of this component
   * round the same number to the same string.
   *
   * A bound that was left out is filled from the style, and a maximum below its minimum is
   * clamped rather than rejected. `Intl` throws on that pair; a formatter that refuses to draw is
   * worse than a number carrying one more decimal than was asked for.
   */
  private static func bounds(
    _ minimum: Int,
    _ maximum: Int,
    defaultMinimum: Int,
    defaultMaximum: Int
  ) -> (Int, Int) {
    if minimum >= 0 && maximum >= 0 { return (minimum, max(minimum, maximum)) }
    if minimum >= 0 { return (minimum, max(defaultMaximum, minimum)) }
    if maximum >= 0 { return (min(defaultMinimum, maximum), maximum) }
    return (defaultMinimum, defaultMaximum)
  }
}

/// The props the SwiftUI tree reads. A class, so updating it re-renders in place rather than
/// rebuilding the hosting controller — recreating the root view would restart the transition.
private final class NumericTextModel: ObservableObject {
  @Published var text: String = ""
  @Published var value: Double = 0
  @Published var countsDown: Bool = false
  @Published var animates: Bool = false
  @Published var fontSize: CGFloat = 48
  @Published var weight: Font.Weight = .regular
  @Published var fontFamily: String?
  @Published var color: Color = .black
}

private struct NumericTextRoot: View {
  @ObservedObject fileprivate var model: NumericTextModel

  /// The animation the transition runs on — and it is OURS, not Apple's.
  ///
  /// `.numericText()` has no clock of its own: it is driven by whatever animation is in the
  /// transaction. Everything measured about the reference so far has therefore been the
  /// transition's curves multiplied by this spring, solved together.
  ///
  /// `NUMERICTEXT_LINEAR=<seconds>` swaps the spring for a linear ramp, which separates them. Two
  /// things become readable that are not readable otherwise:
  ///
  ///  - every curve — offset, scale, alpha, blur — sampled directly against a known time base,
  ///    instead of being fitted through an unknown one;
  ///  - what the transition IS. At a long duration under a fast alternation, a STACK of
  ///    overlapping transitions puts many distinct glyph forms on screen at once, while a single
  ///    position on a strip can only ever show the two stops around it. Counting them settles the
  ///    question that the layer tree and the `TextRenderer` both refused to answer.
  ///
  /// DEBUG-only and off unless set, exactly like the recorder and the probes.
  /// `NUMERICTEXT_LINEAR=none` removes the animation entirely. That is the CONTROL: with no
  /// animation the value must snap, so if it snaps the branch below is reached and whatever the
  /// transition does with a `.linear` is a real finding rather than a switch that never fired.
  private var transitionAnimation: Animation? {
    #if DEBUG
      let raw = ProcessInfo.processInfo.environment["NUMERICTEXT_LINEAR"]
      if raw == "none" {
        print("[numerictext] transition animation: NONE")
        return nil
      }
      if let seconds = raw.flatMap(Double.init), seconds > 0 {
        print("[numerictext] transition animation: linear \(seconds)s")
        return .linear(duration: seconds)
      }
      print("[numerictext] transition animation: spring (default)")
    #endif
    return .spring()
  }

  var body: some View {
    Text(model.text)
      .font(font)
      .monospacedDigit()
      .foregroundStyle(model.color)
      .numericTextTransition(countsDown: model.countsDown)
      .debugSliceProbe()
      .animation(model.animates ? transitionAnimation : nil, value: model.value)
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .mask(edgeFadeMask)
  }

  private var edgeFadeMask: some View {
    VStack(spacing: 0) {
      LinearGradient(
        gradient: Gradient(stops: [
          .init(color: .clear, location: 0),
          .init(color: .white, location: 0.15),
        ]),
        startPoint: .top,
        endPoint: .bottom
      )
      Color.white
      LinearGradient(
        gradient: Gradient(stops: [
          .init(color: .white, location: 0.85),
          .init(color: .clear, location: 1),
        ]),
        startPoint: .top,
        endPoint: .bottom
      )
    }
  }

  /// The bundled-font equivalent of Android's default: a rounded system face unless the caller
  /// named a family. `system` is the documented opt-out.
  private var font: Font {
    guard let family = model.fontFamily,
      !family.isEmpty,
      family != "system",
      UIFont(name: family, size: model.fontSize) != nil
    else {
      return .system(
        size: model.fontSize,
        weight: model.weight,
        design: model.fontFamily == "system" ? .default : .rounded
      )
    }
    return .custom(family, size: model.fontSize).weight(model.weight)
  }
}

#if DEBUG
/**
 * GROUND-TRUTH PROBE — temporary scaffolding, not part of the shipped renderer.
 *
 * The Android renderer is tuned by comparing screen recordings, which measures how much ink is on
 * screen and leaves us to infer what SwiftUI actually did. This asks the animation directly: for
 * every display frame of a transition it walks the CALayer subtree under the hosting controller and
 * records each layer's live (`presentation()`) geometry, opacity and filters, plus any CAAnimation
 * attached to it.
 *
 * It answers one question, and the answer decides the whole approach: does `.numericText()` expose
 * its per-digit motion as layers we can read, or is it one opaque layer that redraws itself?
 */
final class NumericTextLayerProbe {
  static let shared = NumericTextLayerProbe()

  private var link: CADisplayLink?
  private weak var root: UIView?
  private var startedAt: CFTimeInterval = 0
  private var deadline: CFTimeInterval = 0
  private var frames: [[String: Any]] = []
  private var label: String = ""
  private var countsDown: Bool = false
  private var marks: [[String: Any]] = []
  /// What the text renderer saw, if the slice probe is switched on. Time-aligned with `frames`.
  private var draws: [[String: Any]] = []

  /// Off unless asked for. A display link that walks the layer tree every frame is not free, and a
  /// Debug build is exactly what the video captures are recorded from — the probe must not be in
  /// the picture unless someone wants it there.
  static let enabled = ProcessInfo.processInfo.environment["NUMERICTEXT_PROBE"] == "1"

  /// Whether to interpose a custom `TextRenderer`. Separate switch, because interposing one is
  /// measurably heavier: with it in place SwiftUI rasterises through an image queue and drops
  /// frames, so it must never be on during a measurement run.
  static let sliceProbeEnabled =
    ProcessInfo.processInfo.environment["NUMERICTEXT_SLICE_PROBE"] == "1"

  func recordDraw(_ entry: [String: Any]) {
    guard link != nil else { return }
    var timed = entry
    timed["t"] = (CACurrentMediaTime() - startedAt) * 1000
    draws.append(timed)
  }

  /// How long to keep recording after the last value change.
  private let tail: CFTimeInterval = 1.4

  func arm(root: UIView, label: String, countsDown: Bool) {
    guard Self.enabled else { return }
    let now = CACurrentMediaTime()
    deadline = now + tail
    if link != nil {
      // A burst: keep one continuous recording and just note when each change landed.
      marks.append(["t": (now - startedAt) * 1000, "label": label])
      return
    }
    self.root = root
    self.label = label
    self.countsDown = countsDown
    frames.removeAll()
    draws.removeAll()
    marks = [["t": 0.0, "label": label]]
    startedAt = now

    let displayLink = CADisplayLink(target: self, selector: #selector(tick))
    displayLink.add(to: .main, forMode: .common)
    link = displayLink
    NSLog("[numerictext-probe] armed label=%@ countsDown=%d", label, countsDown ? 1 : 0)
  }

  @objc private func tick(_ sender: CADisplayLink) {
    guard let layer = root?.layer else { return stop() }
    let now = CACurrentMediaTime()
    var nodes: [[String: Any]] = []
    walk(layer, path: "0", into: &nodes)
    frames.append([
      "t": (now - startedAt) * 1000,
      "target": (sender.targetTimestamp - sender.timestamp) * 1000,
      "layers": nodes,
    ])
    if now >= deadline { stop() }
  }

  private func stop() {
    link?.invalidate()
    link = nil
    guard !frames.isEmpty else { return }

    let layerCounts = frames.map { ($0["layers"] as? [[String: Any]])?.count ?? 0 }
    let peak = frames
      .max { (($0["layers"] as? [[String: Any]])?.count ?? 0) < (($1["layers"] as? [[String: Any]])?.count ?? 0) }
    let classes = (peak?["layers"] as? [[String: Any]])?.map { ($0["class"] as? String) ?? "?" } ?? []
    let animated = (peak?["layers"] as? [[String: Any]])?.filter { $0["animations"] != nil }.count ?? 0
    NSLog(
      "[numerictext-probe] frames=%d layers min=%d peak=%d animatedLayers=%d classes=%@",
      frames.count,
      layerCounts.min() ?? 0,
      layerCounts.max() ?? 0,
      animated,
      classes.joined(separator: ",")
    )
    if Self.sliceProbeEnabled {
      let sliceCounts = draws.map { ($0["slices"] as? [[String: Any]])?.count ?? 0 }
      NSLog(
        "[numerictext-probe] draws=%d slicesPerDraw min=%d max=%d",
        draws.count,
        sliceCounts.min() ?? 0,
        sliceCounts.max() ?? 0
      )
    }

    let payload: [String: Any] = [
      "label": label,
      "countsDown": countsDown,
      "scale": UIScreen.main.scale,
      "sliceProbe": Self.sliceProbeEnabled,
      "marks": marks,
      "frames": frames,
      "draws": draws,
    ]
    frames.removeAll()
    draws.removeAll()
    write(payload)
  }

  private func write(_ payload: [String: Any]) {
    guard
      let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
        .appendingPathComponent("numerictext-probe", isDirectory: true),
      let data = try? JSONSerialization.data(withJSONObject: payload, options: [])
    else { return }
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let name = "probe-\(Int(Date().timeIntervalSince1970 * 1000)).json"
    let url = dir.appendingPathComponent(name)
    try? data.write(to: url)
    NSLog("[numerictext-probe] wrote %@", url.path)
  }

  // MARK: - Layer capture

  private func walk(_ layer: CALayer, path: String, into out: inout [[String: Any]]) {
    guard out.count < 400 else { return }
    out.append(describe(layer, path: path))
    for (index, sub) in (layer.sublayers ?? []).enumerated() {
      walk(sub, path: "\(path).\(index)", into: &out)
    }
  }

  private func describe(_ layer: CALayer, path: String) -> [String: Any] {
    // `presentation()` is what is actually on screen this frame; the model layer holds the
    // destination. Recording the model layer instead is the classic way to measure an animation and
    // see a perfectly flat line.
    let live = layer.presentation() ?? layer
    let t = live.transform
    var entry: [String: Any] = [
      "path": path,
      "class": String(describing: type(of: layer)),
      "bounds": [live.bounds.origin.x, live.bounds.origin.y, live.bounds.width, live.bounds.height],
      "position": [live.position.x, live.position.y],
      "anchor": [live.anchorPoint.x, live.anchorPoint.y],
      "opacity": live.opacity,
      "hidden": live.isHidden,
      // Row-major flattening of CATransform3D; for our purposes m11/m22 are scale, m41/m42 offset.
      "transform": [t.m11, t.m12, t.m21, t.m22, t.m41, t.m42],
      "sublayers": layer.sublayers?.count ?? 0,
    ]
    if let contents = live.contents {
      entry["contents"] = String(describing: type(of: contents as AnyObject))
      if CFGetTypeID(contents as CFTypeRef) == CGImage.typeID {
        let image = contents as! CGImage
        entry["contentsSize"] = [image.width, image.height]
      }
    }
    if let mask = layer.mask { entry["mask"] = String(describing: type(of: mask)) }
    if let filters = layer.filters, !filters.isEmpty {
      entry["filters"] = filters.map { filterDescription($0) }
    }
    if let compositing = layer.compositingFilter {
      entry["compositingFilter"] = filterDescription(compositing)
    }
    if let keys = layer.animationKeys(), !keys.isEmpty {
      entry["animations"] = keys.compactMap { key -> [String: Any]? in
        guard let animation = layer.animation(forKey: key) else { return nil }
        return animationDescription(key: key, animation)
      }
    }
    return entry
  }

  private func filterDescription(_ filter: Any) -> [String: Any] {
    var described: [String: Any] = ["class": String(describing: type(of: filter as AnyObject))]
    guard let object = filter as? NSObject else { return described }
    // CAFilter is private, so everything here is read by key and may simply not be there.
    for key in ["name", "type", "inputRadius", "inputAmount", "inputIntensity"] {
      guard object.responds(to: NSSelectorFromString(key)) else { continue }
      if let value = object.value(forKey: key) {
        described[key] = (value as? NSNumber)?.doubleValue ?? String(describing: value)
      }
    }
    return described
  }

  private func animationDescription(key: String, _ animation: CAAnimation) -> [String: Any] {
    var described: [String: Any] = [
      "key": key,
      "class": String(describing: type(of: animation)),
      "duration": animation.duration,
      "beginTime": animation.beginTime,
    ]
    if let basic = animation as? CABasicAnimation {
      described["keyPath"] = basic.keyPath ?? ""
      described["from"] = String(describing: basic.fromValue)
      described["to"] = String(describing: basic.toValue)
    }
    // The jackpot case: SwiftUI handing CoreAnimation its own spring, parameters and all.
    if let spring = animation as? CASpringAnimation {
      described["spring"] = [
        "mass": spring.mass,
        "stiffness": spring.stiffness,
        "damping": spring.damping,
        "initialVelocity": spring.initialVelocity,
        "settling": spring.settlingDuration,
      ]
    }
    if let group = animation as? CAAnimationGroup {
      described["children"] = (group.animations ?? []).map {
        animationDescription(key: "group", $0)
      }
    }
    if let timing = animation.timingFunction {
      var points = [Float](repeating: 0, count: 8)
      for index in 0..<4 { timing.getControlPoint(at: index, values: &points[index * 2]) }
      described["timing"] = points
    }
    return described
  }
}
/**
 * GROUND TRUTH — the exact-frame recorder.
 *
 * Two probes established that `.numericText()` is closed: SwiftUI rasterises the text once per
 * value and animates the raster itself, with no CALayer per digit, no CAAnimation, and no second
 * call into a custom `TextRenderer`. So the reference can only be read from its pixels.
 *
 * What this removes is every source of error between those pixels and a number. A screen recording
 * is variable-rate, lossy, resampled onto a 60 Hz grid it never had, and its t=0 has to be found by
 * looking for a flash. Here each frame is the layer rendered straight into an 8-bit alpha buffer at
 * device scale, stamped with the display link's own timestamp, and every value change is recorded
 * as a mark on that same clock. Nothing is compressed, nothing is resampled, and the moment each
 * change landed is known rather than inferred.
 *
 * Alpha rather than colour on purpose: the text is one solid colour, so the alpha plane *is* the
 * ink coverage the analysis measures — opacity, blur and the edge-fade mask all multiply into it.
 * One byte per pixel keeps a whole run in memory, so nothing is encoded on the render thread.
 *
 * Output is a raw `.bin` of concatenated frames plus a `.json` describing them. Off unless
 * `NUMERICTEXT_RECORD=1`.
 */
final class NumericTextFrameRecorder {
  static let shared = NumericTextFrameRecorder()
  static let enabled = ProcessInfo.processInfo.environment["NUMERICTEXT_RECORD"] == "1"

  /// Captured area, as a fraction of the view's own bounds. The number moves outside its box during
  /// a transition and a structural change makes it wider; the margin keeps all of that in frame.
  private let margin: CGFloat = 0.35
  /// How long to keep recording after the last value change.
  private let tail: CFTimeInterval = 1.6

  private var link: CADisplayLink?
  private weak var root: UIView?
  private var context: CGContext?
  private var pixelWidth = 0
  private var pixelHeight = 0
  private var scale: CGFloat = 1
  private var captureRect: CGRect = .zero

  private var startedAt: CFTimeInterval = 0
  private var deadline: CFTimeInterval = 0
  private var label = ""
  private var countsDown = false
  private var times: [Double] = []
  private var drawBounds: [[Double]] = []
  private var marks: [[String: Any]] = []

  /// Frames are streamed to disk as they are captured, never accumulated.
  ///
  /// They used to be appended to one in-memory `Data` and written at `stop`. A short run survives
  /// that; a burst does not. 691 frames of 1382x643 is 614 MB, the write failed, `try?` swallowed
  /// it, and what landed on disk was a valid .json beside a zero-byte .bin — which is how every
  /// multi-change recording on this simulator turned out to be unreadable, silently.
  private var planeHandle: FileHandle?
  private var stamp = 0
  private var bytesWritten = 0
  private var truncated = false

  /// A ceiling on one run. At 888 KB a frame this is about 22 minutes, and it exists so a recorder
  /// left armed cannot fill the disk — which has happened to the host and to the emulator today.
  private let byteCap = 1_200_000_000

  func arm(root: UIView, label: String, countsDown: Bool) {
    guard Self.enabled else { return }
    let now = CACurrentMediaTime()
    deadline = now + tail
    if link != nil {
      // A burst stays one recording; the marks are what make the cadence readable afterwards.
      marks.append(["t": (now - startedAt) * 1000, "label": label])
      return
    }
    guard let context = makeContext(for: root) else { return }
    self.root = root
    self.context = context
    self.label = label
    self.countsDown = countsDown
    startedAt = now
    times.removeAll()
    drawBounds.removeAll()
    marks = [["t": 0.0, "label": label]]
    bytesWritten = 0
    truncated = false
    guard openPlaneFile() else { return }

    let displayLink = CADisplayLink(target: self, selector: #selector(tick))
    displayLink.add(to: .main, forMode: .common)
    link = displayLink
  }

  private func makeContext(for view: UIView) -> CGContext? {
    let bounds = view.bounds
    guard bounds.width > 1, bounds.height > 1 else { return nil }
    scale = view.window?.screen.scale ?? UIScreen.main.scale
    captureRect = bounds.insetBy(dx: -bounds.width * margin, dy: -bounds.height * margin)
    pixelWidth = Int((captureRect.width * scale).rounded())
    pixelHeight = Int((captureRect.height * scale).rounded())
    // alphaOnly: one byte of coverage per pixel, which is exactly what the analysis reads.
    return CGContext(
      data: nil,
      width: pixelWidth,
      height: pixelHeight,
      bitsPerComponent: 8,
      bytesPerRow: pixelWidth,
      space: CGColorSpaceCreateDeviceGray(),
      bitmapInfo: CGImageAlphaInfo.alphaOnly.rawValue
    )
  }

  @objc private func tick(_ sender: CADisplayLink) {
    guard let view = root, let context, let data = context.data else { return stop() }
    let now = CACurrentMediaTime()

    context.clear(CGRect(x: 0, y: 0, width: pixelWidth, height: pixelHeight))
    context.saveGState()
    // A bitmap context is bottom-left origin; the layer tree is top-left. Flip once here so the
    // frames come out the same way up as a screenshot.
    context.translateBy(x: 0, y: CGFloat(pixelHeight))
    context.scaleBy(x: scale, y: -scale)
    context.translateBy(x: -captureRect.minX, y: -captureRect.minY)
    // The presentation layer is what is on screen this frame; the model layer is the destination.
    (view.layer.presentation() ?? view.layer).render(in: context)
    context.restoreGState()

    let count = pixelWidth * pixelHeight
    guard let handle = planeHandle else { return stop() }
    do {
      try handle.write(contentsOf: Data(bytes: data, count: count))
    } catch {
      NSLog("[numerictext-record] FRAME WRITE FAILED at %d frames: %@",
            times.count, String(describing: error))
      truncated = true
      return stop()
    }
    bytesWritten += count
    times.append((now - startedAt) * 1000)
    drawBounds.append(drawingBounds(of: view.layer))

    if bytesWritten >= byteCap {
      NSLog("[numerictext-record] hit the %d byte cap after %d frames, stopping",
            byteCap, times.count)
      truncated = true
      return stop()
    }
    if now >= deadline { stop() }
  }

  /// SwiftUI sizes its drawing layer to exactly the ink it is about to lay down, every frame. That
  /// is the reference stating its own vertical extent, so it is worth keeping alongside the pixels.
  private func drawingBounds(of layer: CALayer) -> [Double] {
    var found: CALayer?
    var queue = layer.sublayers ?? []
    while !queue.isEmpty {
      let next = queue.removeFirst()
      if String(describing: type(of: next)).contains("DrawingLayer") { found = next; break }
      queue.append(contentsOf: next.sublayers ?? [])
    }
    guard let drawing = found else { return [] }
    let live = drawing.presentation() ?? drawing
    let frame = live.frame
    return [frame.minX, frame.minY, frame.width, frame.height]
  }

  private func stop() {
    link?.invalidate()
    link = nil
    context = nil
    guard !times.isEmpty else {
      try? planeHandle?.close()
      planeHandle = nil
      return
    }

    let meta: [String: Any] = [
      "label": label,
      "countsDown": countsDown,
      "width": pixelWidth,
      "height": pixelHeight,
      "scale": scale,
      "captureRect": [
        captureRect.minX, captureRect.minY, captureRect.width, captureRect.height,
      ],
      "viewBounds": [root?.bounds.width ?? 0, root?.bounds.height ?? 0],
      "format": "gray8-alpha",
      "frames": times.count,
      "times": times,
      "drawBounds": drawBounds,
      "marks": marks,
      // Set when the run was cut short by the byte cap or by a failed write. The reader must not
      // treat a truncated run as a complete one.
      "truncated": truncated,
      "bytes": bytesWritten,
    ]
    writeMeta(meta)
    NSLog(
      "[numerictext-record] %@ frames=%d %dx%d marks=%d",
      label,
      times.count,
      pixelWidth,
      pixelHeight,
      marks.count
    )
  }

  private func recordingDirectory() -> URL? {
    guard
      let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
        .appendingPathComponent("numerictext-record", isDirectory: true)
    else { return nil }
    do {
      try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    } catch {
      NSLog("[numerictext-record] cannot make the directory: %@", String(describing: error))
      return nil
    }
    return dir
  }

  /// Opens the run's plane file up front, so `tick` only ever appends.
  private func openPlaneFile() -> Bool {
    guard let dir = recordingDirectory() else { return false }
    stamp = Int(Date().timeIntervalSince1970 * 1000)
    let url = dir.appendingPathComponent("run-\(stamp).bin")
    guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
      NSLog("[numerictext-record] cannot create %@", url.lastPathComponent)
      return false
    }
    do {
      planeHandle = try FileHandle(forWritingTo: url)
    } catch {
      NSLog("[numerictext-record] cannot open %@: %@", url.lastPathComponent,
            String(describing: error))
      return false
    }
    return true
  }

  private func writeMeta(_ meta: [String: Any]) {
    // The planes are already on disk; closing the handle is what makes the last of them durable,
    // so it has to happen before the .json that claims they are there.
    try? planeHandle?.close()
    planeHandle = nil
    guard let dir = recordingDirectory() else { return }
    guard let json = try? JSONSerialization.data(withJSONObject: meta, options: []) else {
      NSLog("[numerictext-record] cannot encode the metadata for run-%@", String(stamp))
      return
    }
    do {
      try json.write(to: dir.appendingPathComponent("run-\(stamp).json"))
    } catch {
      NSLog("[numerictext-record] cannot write run-%@.json: %@", String(stamp),
            String(describing: error))
      return
    }
    // %@ with a String, not %d: the stamp is a 64-bit Int and NSLog's %d truncated it to a
    // negative number, so the log named a file that does not exist.
    NSLog("[numerictext-record] wrote run-%@.{bin,json} — %@ bytes%@",
          String(stamp), String(bytesWritten), truncated ? " (TRUNCATED)" : "")
  }
}

/**
 * GROUND-TRUTH PROBE, second attempt — is the transition visible at the level of individual glyphs?
 *
 * The layer probe established that `.numericText()` is drawn by SwiftUI itself into one opaque
 * layer, so there is no per-digit CALayer to read. A custom `TextRenderer` is the other place the
 * text passes through on its way to the screen: it hands us the laid-out text broken into lines,
 * runs and per-glyph slices, and we do the drawing.
 *
 * The question is whether the layout we are handed is the ANIMATED one. `Text.LayoutOptions` has a
 * `disablesAnimations` flag, which implies the default layout carries animation state — if it does,
 * each frame's slices give us every digit's live position, which is the ground truth the whole
 * measurement effort is about.
 *
 * It may equally turn out that interposing a renderer suppresses the transition altogether. That is
 * also an answer, and the reason this is off unless `NUMERICTEXT_SLICE_PROBE=1`.
 */
@available(iOS 18.0, *)
struct NumericTextSliceProbe: TextRenderer {
  func draw(layout: Text.Layout, in context: inout GraphicsContext) {
    var slices: [[String: Any]] = []
    for (lineIndex, line) in layout.enumerated() {
      for (runIndex, run) in line.enumerated() {
        let bounds = run.typographicBounds
        slices.append([
          "kind": "run",
          "line": lineIndex,
          "run": runIndex,
          "count": run.count,
          "rect": [
            bounds.rect.minX, bounds.rect.minY, bounds.rect.width, bounds.rect.height,
          ],
          "ascent": bounds.ascent,
          "descent": bounds.descent,
        ])
        for (sliceIndex, slice) in run.enumerated() {
          let sliceBounds = slice.typographicBounds
          slices.append([
            "kind": "slice",
            "line": lineIndex,
            "run": runIndex,
            "slice": sliceIndex,
            "rect": [
              sliceBounds.rect.minX, sliceBounds.rect.minY,
              sliceBounds.rect.width, sliceBounds.rect.height,
            ],
          ])
        }
      }
    }
    let transform = context.transform
    NumericTextLayerProbe.shared.recordDraw([
      "slices": slices,
      "opacity": context.opacity,
      "transform": [transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty],
      "clip": [
        context.clipBoundingRect.minX, context.clipBoundingRect.minY,
        context.clipBoundingRect.width, context.clipBoundingRect.height,
      ],
    ])

    // Draw exactly what we were given, so the probe changes what is measured as little as possible.
    for line in layout {
      context.draw(line)
    }
  }
}
#endif

extension View {
  /// Interposes the slice probe when it is switched on, and is the identity otherwise.
  @ViewBuilder
  fileprivate func debugSliceProbe() -> some View {
    #if DEBUG
    if #available(iOS 18.0, *), NumericTextLayerProbe.sliceProbeEnabled {
      self.textRenderer(NumericTextSliceProbe())
    } else {
      self
    }
    #else
    self
    #endif
  }

  /**
   * `.numericText()`, where the OS has it.
   *
   * Both forms of the transition — `countsDown:` and `value:` — are iOS 17, so below that there is
   * no numeric content transition to ask for and the number simply cuts to its new value. The
   * library's floor is lower than 17 (React Native's is 15.1), so this cannot be an `@available`
   * on the type.
   */
  @ViewBuilder
  fileprivate func numericTextTransition(countsDown: Bool) -> some View {
    if #available(iOS 17.0, *) {
      self.contentTransition(.numericText(countsDown: countsDown))
    } else {
      self
    }
  }
}
