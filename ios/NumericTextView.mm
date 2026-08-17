#import "NumericTextView.h"

#import <React/RCTConversions.h>

#import <react/renderer/components/NumericTextViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/NumericTextViewSpec/Props.h>
#import <react/renderer/components/NumericTextViewSpec/RCTComponentViewHelpers.h>

#import "RCTFabricComponentsPlugins.h"

// The SwiftUI half of this view. CocoaPods writes the interop header beside the pod's other
// headers when it builds as a static library, and inside the framework when it does not.
#if __has_include(<NumericText/NumericText-Swift.h>)
#import <NumericText/NumericText-Swift.h>
#else
#import "NumericText-Swift.h"
#endif

using namespace facebook::react;

/**
 * iOS does not reimplement the transition — it asks SwiftUI for the real one.
 *
 * All this class does is translate Fabric props into one call on the SwiftUI host, which is created
 * once in `initWithFrame:` and updated in place. Recreating it on a prop change would restart the
 * roll mid-flight, which is exactly the bug the Android renderer spent weeks unlearning.
 */
@implementation NumericTextView {
  NumericTextSwiftUIHost *_host;
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

    _host = [[NumericTextSwiftUIHost alloc] initWithFrame:frame];
    self.contentView = _host;
  }

  return self;
}

- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps
{
  const auto &next = *std::static_pointer_cast<NumericTextViewProps const>(props);

  // Every prop is forwarded on every update rather than diffed here. The SwiftUI side ignores an
  // unchanged value on its own, and a partial forward is how a number ends up still drawn in the
  // previous font after a style change.
  //
  // Formatting first: the value is drawn through it, so a change to both in one commit has to
  // reach the formatter before it reaches the text.
  [_host applyFormatWithLocale:RCTNSStringFromString(next.locale)
                   numberStyle:RCTNSStringFromString(next.numberStyle)
                      currency:RCTNSStringFromString(next.currency)
               currencyDisplay:RCTNSStringFromString(next.currencyDisplay)
                  currencySign:RCTNSStringFromString(next.currencySign)
                   useGrouping:next.useGrouping
          minimumIntegerDigits:next.minimumIntegerDigits
         minimumFractionDigits:next.minimumFractionDigits
         maximumFractionDigits:next.maximumFractionDigits
      minimumSignificantDigits:next.minimumSignificantDigits
      maximumSignificantDigits:next.maximumSignificantDigits
       trailingDecimalSeparator:NO];

  [_host applyValue:next.value
          direction:RCTNSStringFromString(next.direction)
       reduceMotion:RCTNSStringFromString(next.reduceMotion)
           fontSize:next.fontSize
         fontWeight:RCTNSStringFromString(next.fontWeight)
         fontFamily:RCTNSStringFromString(next.fontFamily)
          textColor:RCTUIColorFromSharedColor(next.textColor)];

  [super updateProps:props oldProps:oldProps];
}

@end
