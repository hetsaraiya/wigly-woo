#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Runs the block and returns the NSException it raised, or nil on success.
/// Foundation's websocket task factory can throw ObjC exceptions that Swift
/// cannot catch; without this trampoline they abort the whole app.
NSException * _Nullable WiglyCatchException(void (NS_NOESCAPE ^block)(void));

NS_ASSUME_NONNULL_END
