#import <React/RCTViewComponentView.h>
#import <UIKit/UIKit.h>

#ifndef NumericTextViewNativeComponent_h
#define NumericTextViewNativeComponent_h

NS_ASSUME_NONNULL_BEGIN

@interface NumericTextView : RCTViewComponentView

@property (nonatomic, assign) double numericValue;
@property (nonatomic, copy) NSString *numericLocale;
@property (nonatomic, copy) NSString *numericDirection;
@property (nonatomic, assign) double numericAnimationDuration;
@property (nonatomic, assign) BOOL numericUseGrouping;
@property (nonatomic, assign) NSInteger numericMinFractionDigits;
@property (nonatomic, assign) NSInteger numericMaxFractionDigits;
@property (nonatomic, copy) NSString *numericReduceMotion;
@property (nonatomic, assign) CGFloat numericFontSize;
@property (nonatomic, copy) NSString *numericFontWeight;
@property (nonatomic, strong, nullable) UIColor *numericTextColor;

@end

NS_ASSUME_NONNULL_END

#endif /* NumericTextViewNativeComponent_h */
