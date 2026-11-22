/* Persistent offline PMK producer. Runs as shell UID inside benchmark-guard.
 * No radio, arbitrary executable, or caller-selected destination paths. */
#define CL_TARGET_OPENCL_VERSION 120
#define _GNU_SOURCE
#include <CL/cl.h>
#include <dlfcn.h>
#include <openssl/evp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/stat.h>
#include <limits.h>
#include "pmk-sha1.h"
#define BASE "/data/local/tmp/lambda-crack-bench"
#define N 19200
#define API(n) __typeof__(&n) p_##n=(void *)dlsym(lib,#n);if(!p_##n)return 70
#define CHECK(x) do{cl_int err_=(x);if(err_!=CL_SUCCESS){fprintf(stderr,"OpenCL %d line %d\n",err_,__LINE__);return 71;}}while(0)
typedef struct{cl_uint ip[5],op[5],u[10],sum[10];} state_t;
typedef struct{unsigned char password[16],pmk[32];} record_t;
static record_t records[N];static unsigned char passwords[N*16],salt[32];static unsigned count,gpu_count,slen;
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9;}
static void *cpu_work(void *arg){unsigned id=*(unsigned *)arg;for(unsigned i=gpu_count+id;i<count;i+=8)lambda_pmk(records[i].password,strlen((char *)records[i].password),salt,slen,records[i].pmk);return NULL;}
static unsigned read_batch(void){
 FILE *f=fopen("batch.txt","rb");if(!f)return 0;char line[128];unsigned n=0;
 memset(records,0,sizeof(records));memset(passwords,0,sizeof(passwords));
 while(fgets(line,sizeof(line),f)){
  size_t len=strlen(line);if(!len||line[len-1]!='\n'||n>=N){fclose(f);return 0;}line[--len]=0;
  if(len<8||len>15){fclose(f);return 0;}for(size_t j=0;j<len;j++)if((unsigned char)line[j]<32||(unsigned char)line[j]>126){fclose(f);return 0;}
  memcpy(records[n].password,line,len);memcpy(passwords+n*16,line,len);n++;
 }if(ferror(f)){fclose(f);return 0;}fclose(f);return n;
}
static int refcheck(unsigned i){unsigned char ref[32];return PKCS5_PBKDF2_HMAC_SHA1((char *)records[i].password,strlen((char *)records[i].password),salt,slen,4096,32,ref)&&!memcmp(ref,records[i].pmk,32);}
static int save(void){
 unsigned char head[48]={0};memcpy(head,"LPMKC01",8);head[8]=slen;head[12]=count&255;head[13]=count>>8;memcpy(head+16,salt,slen);
 FILE *f=fopen("pmk-cache.tmp","wb");if(!f)return 0;
 int ok=fwrite(head,1,48,f)==48&&fwrite(records,sizeof(record_t),count,f)==count&&fflush(f)==0&&fsync(fileno(f))==0;
 if(fclose(f))ok=0;return ok&&!rename("pmk-cache.tmp","pmk-cache.bin");
}
int main(int argc,char **argv){
 if(argc!=3||getuid()!=2000)return 64;
 char path[PATH_MAX];if(!realpath(argv[1],path)||strncmp(path,BASE"/runs/crack-",strlen(BASE"/runs/crack-"))||strchr(path+strlen(BASE"/runs/crack-"),'/')||chdir(path))return 64;
 size_t hexlen=strlen(argv[2]);if(hexlen<2||hexlen>64||hexlen%2)return 64;slen=hexlen/2;
 for(unsigned i=0;i<slen;i++){unsigned value;if(sscanf(argv[2]+2*i,"%2x",&value)!=1)return 64;salt[i]=value;}
 double begin=now();void *lib=dlopen("/vendor/lib64/libOpenCL.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return 70;
 API(clGetPlatformIDs);API(clGetDeviceIDs);API(clCreateContext);API(clCreateCommandQueue);API(clCreateProgramWithSource);API(clBuildProgram);API(clGetProgramBuildInfo);API(clCreateKernel);API(clCreateBuffer);API(clSetKernelArg);API(clEnqueueNDRangeKernel);API(clFinish);API(clEnqueueReadBuffer);API(clEnqueueWriteBuffer);API(clReleaseMemObject);API(clReleaseKernel);API(clReleaseProgram);API(clReleaseCommandQueue);API(clReleaseContext);
 cl_platform_id platform;cl_device_id device;cl_int err;
 CHECK(p_clGetPlatformIDs(1,&platform,NULL));CHECK(p_clGetDeviceIDs(platform,CL_DEVICE_TYPE_GPU,1,&device,NULL));
 cl_context ctx=p_clCreateContext(NULL,1,&device,NULL,NULL,&err);CHECK(err);cl_command_queue queue=p_clCreateCommandQueue(ctx,device,0,&err);CHECK(err);
 FILE *f=fopen(BASE"/pmk-hybrid.cl","rb");if(!f)return 72;char source[16384];size_t len=fread(source,1,sizeof(source)-1,f);int extra=fgetc(f);fclose(f);if(extra!=EOF)return 72;source[len]=0;
 const char *ptr=source;cl_program program=p_clCreateProgramWithSource(ctx,1,&ptr,&len,&err);CHECK(err);
 err=p_clBuildProgram(program,1,&device,"-cl-std=CL1.2",NULL,NULL);if(err){char log[4096]={0};p_clGetProgramBuildInfo(program,device,CL_PROGRAM_BUILD_LOG,sizeof(log)-1,log,NULL);fputs(log,stderr);return 72;}
 cl_kernel init=p_clCreateKernel(program,"init",&err);CHECK(err);cl_kernel loop=p_clCreateKernel(program,"loop",&err);CHECK(err);
 state_t *states=calloc(N,sizeof(state_t));if(!states)return 73;
 cl_mem pw=p_clCreateBuffer(ctx,CL_MEM_READ_ONLY,sizeof(passwords),NULL,&err);CHECK(err);cl_mem mem=p_clCreateBuffer(ctx,CL_MEM_READ_WRITE,N*sizeof(state_t),NULL,&err);CHECK(err);cl_mem ssid=p_clCreateBuffer(ctx,CL_MEM_READ_ONLY|CL_MEM_COPY_HOST_PTR,32,salt,&err);CHECK(err);
 CHECK(p_clSetKernelArg(init,0,sizeof(pw),&pw));CHECK(p_clSetKernelArg(init,1,sizeof(mem),&mem));CHECK(p_clSetKernelArg(init,3,sizeof(ssid),&ssid));CHECK(p_clSetKernelArg(init,4,sizeof(slen),&slen));CHECK(p_clSetKernelArg(loop,0,sizeof(mem),&mem));
 printf("LAMBDA_PMK {\"phase\":\"ready\",\"setup_s\":%.6f}\n",now()-begin);fflush(stdout);
 char command[16];unsigned batches=0;
 while(fgets(command,sizeof(command),stdin)){
  if(!strcmp(command,"STOP\n"))break;if(strcmp(command,"NEXT\n")||(count=read_batch())==0)return 64;
  gpu_count=(count+1)/2;size_t global=(gpu_count+31)/32*32,local=32;double start=now();
  CHECK(p_clEnqueueWriteBuffer(queue,pw,CL_TRUE,0,count*16,passwords,0,NULL,NULL));CHECK(p_clSetKernelArg(init,2,sizeof(gpu_count),&gpu_count));CHECK(p_clSetKernelArg(loop,1,sizeof(gpu_count),&gpu_count));
  pthread_t threads[8];unsigned ids[8];for(unsigned i=0;i<8;i++){ids[i]=i;if(pthread_create(threads+i,NULL,cpu_work,ids+i))return 73;}
  CHECK(p_clEnqueueNDRangeKernel(queue,init,1,NULL,&global,&local,0,NULL,NULL));CHECK(p_clFinish(queue));
  for(unsigned remaining=4095;remaining;){cl_uint rounds=remaining>64?64:remaining;remaining-=rounds;CHECK(p_clSetKernelArg(loop,2,sizeof(rounds),&rounds));CHECK(p_clEnqueueNDRangeKernel(queue,loop,1,NULL,&global,&local,0,NULL,NULL));CHECK(p_clFinish(queue));}
  CHECK(p_clEnqueueReadBuffer(queue,mem,CL_TRUE,0,gpu_count*sizeof(state_t),states,0,NULL,NULL));for(unsigned i=0;i<8;i++)pthread_join(threads[i],NULL);
  for(unsigned i=0;i<gpu_count;i++)for(unsigned j=0;j<32;j++)records[i].pmk[j]=states[i].sum[j/4]>>(24-8*(j%4));
  double calc=now()-start;
  // Independent OpenSSL checks across BOTH partitions in every batch. Tests can
  // request all entries; that mode is deliberately not performance reporting.
  unsigned checked=0;int exhaustive=getenv("LAMBDA_VERIFY_ALL")!=NULL;
  for(unsigned i=0;i<count;i++)if(exhaustive||count<=32||i<8||i>=count-8||i==gpu_count-1||i==gpu_count){if(!refcheck(i)){fputs("PMK crosscheck failed\n",stderr);return 74;}checked++;}
  if(!save())return 75;batches++;
  printf("LAMBDA_PMK {\"phase\":\"batch\",\"count\":%u,\"gpu_count\":%u,\"checked\":%u,\"compute_s\":%.6f,\"batch_s\":%.6f,\"batches\":%u}\n",count,gpu_count,checked,calc,now()-start,batches);fflush(stdout);
 }
 p_clReleaseMemObject(pw);p_clReleaseMemObject(mem);p_clReleaseMemObject(ssid);p_clReleaseKernel(init);p_clReleaseKernel(loop);p_clReleaseProgram(program);p_clReleaseCommandQueue(queue);p_clReleaseContext(ctx);free(states);
 // The Mali library owns helper threads. Avoid racing dlclose with their exit.
 return ferror(stdin)?76:0;
}
