#import "../../src/input/InputAssistance.h"
#import <AppKit/AppKit.h>
#include <cassert>

int main() {
    @autoreleasepool {
        [NSApplication sharedApplication];
        NSPasteboard *board=[NSPasteboard pasteboardWithUniqueName];
        __block BOOL current=YES;
        __block NSDictionary *preferences=@{@"clipboard_history":@YES};
        __block NSString *inserted=nil;
        __block NSInteger writes=0;
        MSIMEInputAssistance *assistance=[[MSIMEInputAssistance alloc] initWithPasteboard:board
            preferences:^NSDictionary * { return preferences; } isCurrent:^BOOL { return current; }
            caret:^NSRect { return NSMakeRect(200,500,2,18); }
            insert:^(NSString *text) { inserted=text; writes++; }];
        const auto copy=^(NSString *text) { [board clearContents];[board setString:text forType:NSPasteboardTypeString]; };
        copy(@"合成验证码 482615");
        [assistance sampleAtUptime:NSProcessInfo.processInfo.systemUptime];
        assert([assistance.pendingCode isEqual:@"482615"] && writes==0);
        [assistance accept];[assistance accept];
        assert([inserted isEqual:@"482615"] && writes==1 && !assistance.pendingCode);
        copy(@"合成验证码 619482");
        NSTimeInterval now=NSProcessInfo.processInfo.systemUptime;
        [assistance sampleAtUptime:now];[assistance sampleAtUptime:now+300];
        assert(!assistance.pendingCode);
        copy(@"合成验证码 711806");[assistance sampleAtUptime:NSProcessInfo.processInfo.systemUptime];
        current=NO;[assistance accept];assert(writes==1);
        current=YES;preferences=@{@"clipboard_history":@NO};
        copy(@"合成验证码 482615");[assistance sampleAtUptime:NSProcessInfo.processInfo.systemUptime];assert(!assistance.pendingCode);
        preferences=@{@"clipboard_history":@YES};
        [board clearContents];
        NSPasteboardItem *secret=[NSPasteboardItem new];
        [secret setString:@"合成验证码 482615" forType:NSPasteboardTypeString];
        [secret setString:@"" forType:@"org.nspasteboard.ConcealedType"];
        [board writeObjects:@[secret]];
        [assistance sampleAtUptime:NSProcessInfo.processInfo.systemUptime];assert(!assistance.pendingCode);
        [assistance stop];[board releaseGlobally];
        puts("macOS native OTP: hint, explicit fill, one use, expiry, stale focus and secret markers passed");
    }
}
