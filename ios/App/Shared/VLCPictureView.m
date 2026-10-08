#import "VLCPictureView.h"
#import <AVKit/AVKit.h>
@import VLCKit;

@interface VLCPictureView () <VLCDrawable, VLCPictureInPictureDrawable, VLCPictureInPictureMediaControlling>
@property (nonatomic, weak) VLCMediaPlayer *player;
@property (nonatomic) BOOL wantsPictureInPicture;
@property (nonatomic, strong, nullable) id<VLCPictureInPictureWindowControlling> pip;
@property (nonatomic, readwrite) BOOL pictureInPicturePossible;
@property (nonatomic, readwrite) BOOL pictureInPictureActive;
@end

@implementation VLCPictureView

- (instancetype)initWithPlayer:(VLCMediaPlayer *)player pictureInPicture:(BOOL)pictureInPicture {
    if ((self = [super init])) {
        _player = player;
        _wantsPictureInPicture = pictureInPicture;
        _host = [[UIView alloc] init];
        _host.backgroundColor = UIColor.blackColor;
        // Only a picture: a tap is the player's, to bring its controls back.
        _host.userInteractionEnabled = NO;
    }
    return self;
}

- (id)drawable {
    return self.wantsPictureInPicture ? self : self.host;
}

#pragma mark - VLCDrawable

- (void)addSubview:(UIView *)view {
    view.frame = self.host.bounds;
    view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    view.userInteractionEnabled = NO;
    [self.host addSubview:view];
}

- (CGRect)bounds {
    return self.host.bounds;
}

#pragma mark - VLCPictureInPictureDrawable

- (id<VLCPictureInPictureMediaControlling>)mediaController {
    return self;
}

- (void (^)(id<VLCPictureInPictureWindowControlling>))pictureInPictureReady {
    __weak typeof(self) weakSelf = self;
    return ^(id<VLCPictureInPictureWindowControlling> controller) {
        dispatch_async(dispatch_get_main_queue(), ^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf) return;
            strongSelf.pip = controller;
            controller.stateChangeEventHandler = ^(BOOL isStarted) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    weakSelf.pictureInPictureActive = isStarted;
                    if (weakSelf.onChange) weakSelf.onChange();
                });
            };
            [strongSelf allowStartingByLeaving:controller];
            strongSelf.pictureInPicturePossible = YES;
            if (strongSelf.onChange) strongSelf.onChange();
        });
    };
}

/*
 * Swiping out of the app starts it, as with Apple's player. VLCKit keeps Apple's own
 * controller inside its own and doesn't say so; asked for by name, and left alone if it
 * isn't there, when the button still works.
 */
- (void)allowStartingByLeaving:(id<VLCPictureInPictureWindowControlling>)controller {
    if (@available(iOS 14.2, tvOS 14.2, *)) {
        id inner = nil;
        @try {
            inner = [(NSObject *)controller valueForKey:@"avPipController"];
        } @catch (NSException *exception) {
            inner = nil;
        }
        if ([inner isKindOfClass:[AVPictureInPictureController class]]) {
            ((AVPictureInPictureController *)inner).canStartPictureInPictureAutomaticallyFromInline = YES;
        }
    }
}

- (void)startPictureInPicture { [self.pip startPictureInPicture]; }
- (void)stopPictureInPicture { [self.pip stopPictureInPicture]; }
- (void)invalidate { [self.pip invalidatePlaybackState]; }

#pragma mark - VLCPictureInPictureMediaControlling

- (void)play { [self.player play]; }
- (void)pause { [self.player pause]; }

- (void)seekBy:(int64_t)offset completion:(dispatch_block_t)completion {
    if (![self.player jumpWithOffset:(int)offset completion:completion] && completion) completion();
}

- (int64_t)mediaLength {
    return self.player.media.length.intValue;
}

- (int64_t)mediaTime {
    return self.player.time.intValue;
}

- (BOOL)isMediaSeekable {
    return self.player.seekable;
}

- (BOOL)isMediaPlaying {
    return self.player.isPlaying;
}

@end
