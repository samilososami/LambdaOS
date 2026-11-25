# LambdaOS Android 15 builder profile for a 32 GiB Windows host.
# Install as C:\Users\<user>\.wslconfig and restart WSL, not Windows.
[wsl2]
networkingMode=nat
dnsTunneling=true

memory=27GB
processors=12
swap=12GB
swapFile=C:\\Users\\Usuario\\wsl-swap.vhdx
