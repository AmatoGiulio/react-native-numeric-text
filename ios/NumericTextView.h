#import <React/RCTViewComponentView.h>
#import <UIKit/UIKit.h>

#ifndef NumericTextViewNativeComponent_h
#define NumericTextViewNativeComponent_h

NS_ASSUME_NONNULL_BEGIN

/**
 * The Fabric component view. It draws nothing itself: the number is rendered by
 * `NumericTextSwiftUIHost`, a SwiftUI `Text` with `.contentTransition(.numericText())`.
 */
@interface NumericTextView : RCTViewComponentView

@end

NS_ASSUME_NONNULL_END

#endif /* NumericTextViewNativeComponent_h */
