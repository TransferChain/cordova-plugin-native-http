#import "CryptoTestDelegate.h"
#import <Cordova/CDVSettingsDictionary.h>

@implementation CryptoTestDelegate {
    NSMutableArray *_pending;
    CDVSettingsDictionary *_settings;
}
- (instancetype)init {
    self = [super init];
    if (self) {
        _pending = [NSMutableArray array];
        _settings = [[CDVSettingsDictionary alloc] initWithDictionary:@{}];
    }
    return self;
}
- (CDVSettingsDictionary *)settings { return _settings; }
- (NSString *)pathForResource:(NSString *)path { return path; }
- (CDVPlugin *)getCommandInstance:(NSString *)name { return nil; }
- (void)sendPluginResult:(CDVPluginResult *)result callbackId:(NSString *)callbackId {
    if (self.onResultWithCallbackId) self.onResultWithCallbackId(result, callbackId);
    if (self.onResult) self.onResult(result);
}
- (void)evalJs:(NSString *)js {}
- (void)evalJs:(NSString *)js scheduledOnRunLoop:(BOOL)scheduled {}
- (void)runInBackground:(void (^)(void))block {
    if (self.holdBackground) [_pending addObject:[block copy]];
    else dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), block);
}
- (void)flushBackground {
    NSArray *pending = [_pending copy];
    [_pending removeAllObjects];
    self.holdBackground = NO;
    for (void (^block)(void) in pending) [self runInBackground:block];
}
@end
