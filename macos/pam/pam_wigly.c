/*
 * pam_wigly asks the running Wigly Woo app to approve a sudo prompt.
 * It is not installed by the package. A bug in a loginwindow authorization
 * plugin can lock you out of the Mac, so this module is only for the
 * Terminal sudo path:
 *
 *   auth sufficient pam_wigly.so
 *
 * in /etc/pam.d/sudo, after you copy the built .so into /usr/local/lib/pam.
 * FileVault boot and the lock screen are intentionally not handled.
 */
#include <security/pam_modules.h>
#include <security/pam_appl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

PAM_EXTERN int pam_sm_authenticate(pam_handle_t *pamh, int flags, int argc, const char **argv) {
    const char *user = NULL;
    pam_get_user(pamh, &user, NULL);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return PAM_IGNORE;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, "/tmp/wigly-pam.sock", sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(fd);
        return PAM_IGNORE;
    }
    dprintf(fd, "approve %s\n", user ? user : "");
    char reply[16] = {0};
    ssize_t n = read(fd, reply, sizeof(reply) - 1);
    close(fd);
    if (n > 0 && reply[0] == 'y') return PAM_SUCCESS;
    return PAM_AUTH_ERR;
}

PAM_EXTERN int pam_sm_setcred(pam_handle_t *pamh, int flags, int argc, const char **argv) {
    return PAM_SUCCESS;
}
