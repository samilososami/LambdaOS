/* Offline benchmark only: fixed executables, unprivileged engines, bounded lifetime. */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <grp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define BASE "/data/local/tmp/lambda-crack-bench"
static volatile sig_atomic_t stopping;
static void stop(int sig) { stopping = sig; }
static long field(const char *path, const char *key) {
    FILE *f=fopen(path,"r"); if(!f)return -1;
    char line[256];long n=-1;
    while(fgets(line,sizeof(line),f))if(!strncmp(line,key,strlen(key))){sscanf(line+strlen(key),"%ld",&n);break;}
    fclose(f);return n;
}
static int thermal(void) {
    DIR *d=opendir("/sys/class/thermal");if(!d)return 0;
    struct dirent *e;int hot=0;
    while((e=readdir(d))){
        if(strncmp(e->d_name,"thermal_zone",12))continue;
        char path[320],type[80]={0};
        snprintf(path,sizeof(path),"/sys/class/thermal/%s/type",e->d_name);
        FILE *f=fopen(path,"r");if(!f)continue;fscanf(f,"%79s",type);fclose(f);
        snprintf(path,sizeof(path),"/sys/class/thermal/%s/temp",e->d_name);
        long value=field(path,"");
        if((!strcmp(type,"battery")&&value>=42000)||
           ((!strcmp(type,"BIG")||!strcmp(type,"MID")||!strcmp(type,"LITTLE")||!strcmp(type,"G3D"))&&value>=85000))hot=1;
    }closedir(d);return hot;
}
int main(int argc,char **argv) {
    if(getuid()!=0||argc<3)return 64;
    char *end;long seconds=strtol(argv[1],&end,10);
    if(*end||seconds<1||seconds>(strcmp(argv[2],BASE "/pmk-hybrid-worker")?60:3600))return 64;
    if(strcmp(argv[2],BASE "/aircrack-ng")&&strcmp(argv[2],BASE "/aircrack-ng-sha")&&strcmp(argv[2],BASE "/aircrack-ng-hybrid")&&strcmp(argv[2],BASE "/pmk-hybrid-worker")&&strcmp(argv[2],BASE "/gpu/hashcat")&&strcmp(argv[2],BASE "/gpu626/hashcat")&&strcmp(argv[2],BASE "/pmk-cpu-probe")&&strcmp(argv[2],BASE "/pmk-gpu-probe"))return 64;
    if(thermal()){
        puts("LAMBDA_GUARD {\"exit\":75,\"reason\":\"thermal_limit\",\"max_rss_kib\":0,\"cpu_ms\":0}");return 75;
    }
    if(field("/proc/meminfo","MemAvailable:")<786432){
        puts("LAMBDA_GUARD {\"exit\":75,\"reason\":\"memory_limit\",\"max_rss_kib\":0,\"cpu_ms\":0}");return 75;
    }
    struct sigaction a={.sa_handler=stop};sigemptyset(&a.sa_mask);
    sigaction(SIGTERM,&a,NULL);sigaction(SIGINT,&a,NULL);sigaction(SIGHUP,&a,NULL);sigaction(SIGALRM,&a,NULL);
    pid_t owner=getppid();
    if(owner<=1||prctl(PR_SET_PDEATHSIG,SIGTERM)||getppid()!=owner)return 70;
    pid_t guard=getpid(),child=fork();if(child<0)return 71;
    if(child==0){
        if(setsid()<0)_exit(70);
        /* RLIMIT_CPU sums every thread; the wall-clock alarm is the primary deadline. */
        struct rlimit cpu={(rlim_t)seconds*8+2,(rlim_t)seconds*8+4},files={16*1024*1024,16*1024*1024},core={0,0};
        if(setrlimit(RLIMIT_CPU,&cpu)||setrlimit(RLIMIT_FSIZE,&files)||setrlimit(RLIMIT_CORE,&core))_exit(70);
        /* Do not cap virtual address space: Android's Scudo reserves a large VA arena. */
        if(setgroups(0,NULL)||setgid(2000)||setuid(2000)||prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=guard)_exit(70);
        signal(SIGTERM,SIG_DFL);signal(SIGINT,SIG_DFL);signal(SIGHUP,SIG_DFL);signal(SIGALRM,SIG_DFL);
        execv(argv[2],argv+2);perror("benchmark-guard exec");_exit(127);
    }
    printf("LAMBDA_CHILD %d\n",child);fflush(stdout);alarm((unsigned)seconds);
    int status=0,code=0;const char *reason="exit";struct rusage usage={0};
    char path[80];snprintf(path,sizeof(path),"/proc/%d/status",child);
    unsigned cycle=0;
    for(;;){
        pid_t done=wait4(child,&status,WNOHANG,&usage);
        if(done==child){code=WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);break;}
        if(done<0&&errno!=EINTR)return 71;
        if(cycle++%20==0){
            if(!stopping&&thermal()){stopping=SIGTERM;reason="thermal_limit";}
            if(!stopping&&(field("/proc/meminfo","MemAvailable:")<786432||field(path,"VmRSS:")>1048576)){stopping=SIGTERM;reason="memory_limit";}
        }
        if(stopping){
            if(!strcmp(reason,"exit"))reason=stopping==SIGALRM?"deadline":"cancelled";
            kill(-child,SIGTERM);kill(child,SIGTERM);
            usleep(250000);
            if(wait4(child,&status,WNOHANG,&usage)!=child){kill(-child,SIGKILL);kill(child,SIGKILL);while(wait4(child,&status,0,&usage)<0&&errno==EINTR){}}
            code=stopping==SIGALRM?124:143;break;
        }
        usleep(10000);
    }
    printf("\nLAMBDA_GUARD {\"exit\":%d,\"reason\":\"%s\",\"max_rss_kib\":%ld,\"cpu_ms\":%.1f}\n",code,reason,usage.ru_maxrss,
        1000.0*(usage.ru_utime.tv_sec+usage.ru_stime.tv_sec)+(usage.ru_utime.tv_usec+usage.ru_stime.tv_usec)/1000.0);
    fflush(stdout);return code;
}
