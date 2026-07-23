#import "NumericTextView.h"

#import <React/RCTConversions.h>

#import <react/renderer/components/NumericTextViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/NumericTextViewSpec/Props.h>
#import <react/renderer/components/NumericTextViewSpec/RCTComponentViewHelpers.h>

#import "RCTFabricComponentsPlugins.h"

using namespace facebook::react;

/**
 * iOS NOTE — SwiftUI hosting (Phase 2):
 *
 * To integrate ContentTransition.numericText, replace the placeholder UIView
 * below with a UIHostingController wrapping the SwiftUI view:
 *
 * struct NumericTextSwiftUI: View {
 *   let text: String
 *   let value: Double
 *   let countsDown: Bool
 *   var body: some View {
 *     Text(text)
 *       .monospacedDigit()
 *       .contentTransition(.numericText(value: value))
 *       .animation(.default, value: value)
 *   }
 * }
 *
 * The hosting controller must be created once in initWithFrame and its rootView
 * updated when props change. Do NOT recreate on every update.
 *
 * This file currently stores props but renders a plain UIView.
 * Update for SwiftUI when iOS build environment is available.
 */

@implementation NumericTextView {
    UIView * _view;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider
{
    return concreteComponentDescriptorProvider<NumericTextViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const NumericTextViewProps>();
    _props = defaultProps;

    _view = [[UIView alloc] init];

    self.contentView = _view;
  }

  return self;
}

- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps
{
    const auto &oldViewProps = *std::static_pointer_cast<NumericTextViewProps const>(_props);
    const auto &newViewProps = *std::static_pointer_cast<NumericTextViewProps const>(props);

    if (newViewProps.value != oldViewProps.value) {
        self.numericValue = newViewProps.value;
    }
    if (newViewProps.direction != oldViewProps.direction) {
        self.numericDirection = RCTNSStringFromString(newViewProps.direction);
    }
    if (newViewProps.locale != oldViewProps.locale) {
        self.numericLocale = RCTNSStringFromString(newViewProps.locale);
    }
    if (newViewProps.animationDuration != oldViewProps.animationDuration) {
        self.numericAnimationDuration = newViewProps.animationDuration;
    }
    if (newViewProps.useGrouping != oldViewProps.useGrouping) {
        self.numericUseGrouping = newViewProps.useGrouping;
    }
    if (newViewProps.minimumFractionDigits != oldViewProps.minimumFractionDigits) {
        self.numericMinFractionDigits = newViewProps.minimumFractionDigits;
    }
    if (newViewProps.maximumFractionDigits != oldViewProps.maximumFractionDigits) {
        self.numericMaxFractionDigits = newViewProps.maximumFractionDigits;
    }
    if (newViewProps.reduceMotion != oldViewProps.reduceMotion) {
        self.numericReduceMotion = RCTNSStringFromString(newViewProps.reduceMotion);
    }
    if (newViewProps.fontSize != oldViewProps.fontSize) {
        self.numericFontSize = newViewProps.fontSize;
    }
    if (newViewProps.fontWeight != oldViewProps.fontWeight) {
        self.numericFontWeight = RCTNSStringFromString(newViewProps.fontWeight);
    }
    if (newViewProps.textColor != oldViewProps.textColor) {
        self.numericTextColor = RCTUIColorFromSharedColor(newViewProps.textColor);
    }

    [super updateProps:props oldProps:oldProps];
}

@end
