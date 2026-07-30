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

  // swiftlint:disable:next function_parameter_count
  @objc(applyValue:locale:direction:reduceMotion:useGrouping:minimumFractionDigits:maximumFractionDigits:fontSize:fontWeight:fontFamily:textColor:)
  public func apply(
    value: Double,
    locale: String,
    direction: String,
    reduceMotion: String,
    useGrouping: Bool,
    minimumFractionDigits: Int,
    maximumFractionDigits: Int,
    fontSize: CGFloat,
    fontWeight: String,
    fontFamily: String?,
    textColor: UIColor?
  ) {
    model.text = Self.format(
      value,
      locale: locale,
      useGrouping: useGrouping,
      minimumFractionDigits: minimumFractionDigits,
      maximumFractionDigits: maximumFractionDigits
    )
    model.fontSize = fontSize > 0 ? fontSize : 17
    model.weight = Self.weight(from: fontWeight)
    model.fontFamily = fontFamily
    model.color = textColor.map(Color.init(uiColor:)) ?? .primary

    model.countsDown = Self.countsDown(
      direction: direction,
      value: value,
      previous: lastValue
    )
    // The first render places the number; only later ones are transitions.
    model.animates = lastValue != nil && Self.animates(reduceMotion: reduceMotion)
    model.value = value
    lastValue = value
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

  private static func format(
    _ value: Double,
    locale: String,
    useGrouping: Bool,
    minimumFractionDigits: Int,
    maximumFractionDigits: Int
  ) -> String {
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: locale.isEmpty ? "en-US" : locale)
    formatter.numberStyle = .decimal
    formatter.usesGroupingSeparator = useGrouping
    formatter.minimumFractionDigits = max(0, minimumFractionDigits)
    formatter.maximumFractionDigits = max(
      max(0, minimumFractionDigits),
      maximumFractionDigits
    )
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
  }
}

/// The props the SwiftUI tree reads. A class, so updating it re-renders in place rather than
/// rebuilding the hosting controller — recreating the root view would restart the transition.
private final class NumericTextModel: ObservableObject {
  @Published var text: String = ""
  @Published var value: Double = 0
  @Published var countsDown: Bool = false
  @Published var animates: Bool = false
  @Published var fontSize: CGFloat = 17
  @Published var weight: Font.Weight = .regular
  @Published var fontFamily: String?
  @Published var color: Color = .primary
}

private struct NumericTextRoot: View {
  @ObservedObject fileprivate var model: NumericTextModel

  var body: some View {
    Text(model.text)
      .font(font)
      .monospacedDigit()
      .foregroundStyle(model.color)
      .numericTextTransition(countsDown: model.countsDown)
      // Scoped to `value`: a locale or colour change should repaint, not roll.
      .animation(model.animates ? .spring() : nil, value: model.value)
      .frame(maxWidth: .infinity, maxHeight: .infinity)
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

extension View {
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
