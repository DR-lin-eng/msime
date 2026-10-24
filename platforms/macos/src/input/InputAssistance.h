#import <AppKit/AppKit.h>

/// 提示只跟随当前文本客户端；不读短信数据库，也不保存验证码。
@interface MSIMEInputAssistance : NSObject
- (instancetype)initWithPasteboard:(NSPasteboard *)pasteboard
                       preferences:(NSDictionary *(^)(void))preferences
                         isCurrent:(BOOL (^)(void))isCurrent
                             caret:(NSRect (^)(void))caret
                            insert:(void (^)(NSString *))insert;
- (void)start;
- (void)stop;
- (void)sampleAtUptime:(NSTimeInterval)now;
- (void)accept;
@property(nonatomic, readonly) NSString *pendingCode;
@end

/// 进程级、可推迟的夜间维护；由输入法本体启动并在退出时停止。
void MSIMEStartNightLearning(void);
void MSIMEStopNightLearning(void);
