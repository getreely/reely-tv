#import <UIKit/UIKit.h>

@class VLCMediaPlayer;

NS_ASSUME_NONNULL_BEGIN

/**
 * Where VLC draws a picture: a view of ours VLC puts its own into, which also lets VLC's
 * picture in picture take the picture out of the app, as Apple's player's does. Written in
 * Objective-C, as VLC's own app writes it: VLCKit's drawing protocols aren't marked up for
 * Swift.
 */
@interface VLCPictureView : NSObject

/// The view VLC's picture goes into.
@property (nonatomic, readonly) UIView *host;
/// Picture in picture can be started: VLC has handed over its controller.
@property (nonatomic, readonly) BOOL pictureInPicturePossible;
@property (nonatomic, readonly) BOOL pictureInPictureActive;
/// Called when either of the two above changes.
@property (nonatomic, copy, nullable) void (^onChange)(void);

/// [pictureInPicture] NO for a picture that never leaves the app (a Multiview tile).
- (instancetype)initWithPlayer:(VLCMediaPlayer *)player pictureInPicture:(BOOL)pictureInPicture;
- (instancetype)init NS_UNAVAILABLE;

/// What VLC draws into: this, for picture in picture; else the plain view.
@property (nonatomic, readonly) id drawable;

- (void)startPictureInPicture;
- (void)stopPictureInPicture;
/// Tell picture in picture that what's playing, or whether it is, has changed.
- (void)invalidate;

@end

NS_ASSUME_NONNULL_END
