#import <Foundation/Foundation.h>
#import <Cordova/CDVCommandDelegate.h>
#import <Cordova/CDVPluginResult.h>

NS_ASSUME_NONNULL_BEGIN
@interface CryptoTestDelegate : NSObject <CDVCommandDelegate>
@property(nonatomic, copy, nullable) void (^onResult)(CDVPluginResult *result);
@property(nonatomic, copy, nullable) void (^onResultWithCallbackId)(CDVPluginResult *result, NSString *callbackId);
@property(nonatomic) BOOL holdBackground;
- (void)flushBackground;
@end
NS_ASSUME_NONNULL_END
