// Objective-C interface required by React Native's RCTBridgeModule macro.
// The real implementation lives in RCTDash0MobileModule.swift and is
// resolved via the {ModuleName}-Swift.h header at build time.

#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(Dash0Mobile, NSObject)

RCT_EXTERN_METHOD(start:(NSDictionary *)config
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(emitBatch:(NSArray *)payloads
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(flushWindow:(double)minutes
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(shutdown:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

+ (BOOL)requiresMainQueueSetup {
    return NO;
}

@end
