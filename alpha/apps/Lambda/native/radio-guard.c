/* Lambda development helper: bounded child process + parent-death cleanup.
 * No shell, no user-selectable program outside the three maintenance tools.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static volatile sig_atomic_t stopping;
static void stop(int sig) { stopping = sig; }
int main(int argc, char **argv) {
    if (getuid() != 0 || argc < 4) return 64;
    char *end = NULL;
    long seconds = strtol(argv[1], &end, 10);
    if (*end || seconds < 1 || seconds > 220) return 64;
    if (strcmp(argv[2], "/system/bin/tcpdump") &&
        strcmp(argv[2], "/data/local/tmp/lambda-operator-radio/airodump-ng") &&
        strcmp(argv[2], "/data/local/tmp/lambda-operator-radio/aireplay-ng")) return 64;
    struct sigaction action = {.sa_handler = stop};
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL); sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL); sigaction(SIGALRM, &action, NULL);
    pid_t owner = getppid();
    if (owner <= 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != owner) return 70;
    pid_t guard = getpid();
    pid_t child = fork();
    if (child < 0) return 71;
    if (child == 0) {
        if (setsid() < 0 || prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != guard) _exit(70);
        signal(SIGTERM, SIG_DFL); signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL); signal(SIGALRM, SIG_DFL);
        execv(argv[2], argv + 2); perror("radio-guard exec"); _exit(127);
    }
    alarm((unsigned)seconds);
    int status = 0;
    for (;;) {
        pid_t done = waitpid(child, &status, WNOHANG);
        if (done == child) return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
        if (done < 0 && errno != EINTR) return 71;
        if (stopping) {
            /* Child is unreaped, so this PID cannot be reused. */
            kill(-child, SIGTERM); kill(child, SIGTERM);
            for (int n = 0; n < 10; n++) {
                if (waitpid(child, &status, WNOHANG) == child) return stopping == SIGALRM ? 124 : 143;
                usleep(50000);
            }
            kill(-child, SIGKILL); kill(child, SIGKILL);
            while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
            return stopping == SIGALRM ? 124 : 143;
        }
        usleep(50000);
    }
}
