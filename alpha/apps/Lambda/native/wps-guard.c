/* Fixed OneShot adapter only. Bound its complete process group, including
 * supplicant and pixiewps, on timeout, cancellation and worker death. */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>
static volatile sig_atomic_t stop;
static void stopping(int sig){stop=sig;}
int main(int argc,char **argv){
    int brute=argc==2&&!strcmp(argv[1],"--bruteforce");
    if(getuid()!=0||(argc!=1&&!brute))return 64;
    struct sigaction a={.sa_handler=stopping};sigemptyset(&a.sa_mask);
    sigaction(SIGTERM,&a,0);sigaction(SIGINT,&a,0);sigaction(SIGALRM,&a,0);
    pid_t owner=getppid();
    if(owner<=1||prctl(PR_SET_CHILD_SUBREAPER,1)||prctl(PR_SET_PDEATHSIG,SIGTERM)||getppid()!=owner)return 70;
    pid_t guard=getpid(),child=fork();if(child<0)return 71;
    if(!child){
        if(setsid()<0||prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=guard)_exit(70);
        signal(SIGTERM,SIG_DFL);signal(SIGINT,SIG_DFL);signal(SIGALRM,SIG_DFL);
        execl("/data/local/tmp/lambda-wps/usr/bin/python3","python3","-u","/data/local/tmp/lambda-wps/adapter.py",(char*)0);_exit(127);
    }
    alarm(brute?610:130);int status=0,exited=0;
    while(!stop){
        siginfo_t info={0};
        if(waitid(P_PID,child,&info,WEXITED|WNOHANG|WNOWAIT)<0&&errno!=EINTR){stop=SIGTERM;break;}
        if(info.si_pid==child){exited=1;break;}
        usleep(50000);
    }
    /* Keep child unreaped until group cleanup to avoid PID/PGID reuse. */
    kill(-child,SIGTERM);kill(child,SIGTERM);usleep(500000);
    kill(-child,SIGKILL);kill(child,SIGKILL);
    while(waitpid(child,&status,0)<0&&errno==EINTR){}
    while(waitpid(-1,0,WNOHANG)>0){}
    if(!exited)return stop==SIGALRM?124:143;
    return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);
}
