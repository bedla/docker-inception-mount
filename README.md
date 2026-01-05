# Running Docker in Docker with mounts from Host filesystem using Windows

In this article we will show mounting Host filesystem and running Docker comment from already started Docker container behaves when running solely from Windows 
or using [WSL](https://en.wikipedia.org/wiki/Windows_Subsystem_for_Linux).

Later, based on observations, we will use this knowledge to run example application.

_Note: During investigation I was getting misleading error messages or behavior and because I have [Docker Pro plan](https://www.docker.com/pricing/), I had some discussions with their support.
Some of the output and knowledge is based on those discussions._

## Use-case description

Our use-case is following:
- Start Docker container and mount Host directory to be able to share files
- From started container start another Docker container and mount same Host directory to exchange the files.
- At the end to be able to:
  - see same files from Host in both containers.
  - when file is created in one container, it is possible to access this file from another container, and from the host.
- Because we are running Docker command from running container, we have to be able to connect to Docker daemon. 

## [WORKING] Mounting from Windows command line

Docker Desktop primarily support Window ecosystem. That's why use-case above works perfectly. 

1. We create and verify file in the Host directory
```
host> mkdir c:\Temp\my-project-1
host> echo aaa > c:\Temp\my-project-1\file.txt
host> dir c:\Temp\my-project-1
04.01.2026  13:51                 6 file.txt
```

2. We start Docker container `docker:cli` with shell. And we connect it to Host's Docker daemon using `DOCKER_HOST` environment variable.
```
host> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v c:\Temp\my-project-1:/app docker:cli sh
```

3. Verify that files from host are properly mounted using `ls` command
   - We expect there that there is `file.txt` from Host file-system.
```
docker1> ls -l /app
total 0
-rwxrwxrwx    1 root     root             6 Jan  4 12:51 file.txt
```

4. Create new file `docker1-file.txt` in shared directory, and list files to verify its creation.
```
docker1> / # echo xxx > /app/docker1-file.txt

docker1> / # ls -l /app
total 0
-rw-r--r--    1 root     root             4 Jan  4 12:53 docker1-file.txt
-rwxrwxrwx    1 root     root             6 Jan  4 12:51 file.txt
```

5. Now inside first container, start another container `docker:cli` with shell. We connect it to Host's Docker daemon using `DOCKER_HOST` environment variable.
  - We mount same Host directory `c:\\Temp\\my-project-1` (mind `\\` escaping). 
```
docker1> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v c:\\Temp\\my-project-1:/inception docker:cli sh
```

6. Verify that files from host are properly mounted using `ls` command
  - We expect there that there is `file.txt` from Host file-system.
  - And also file `docker1-file.txt` created inside first container.
```
docker2> / # ls -l /inception
total 0
-rw-r--r--    1 root     root             4 Jan  4 12:53 docker1-file.txt
-rwxrwxrwx    1 root     root             6 Jan  4 12:51 file.txt
```

7. Create new file `docker2-file.txt` in shared directory, and list files to verify its creation.
```
docker2> / # echo yyy > /inception/docker2-file.txt

docker2> / # ls -l /inception
total 0
-rw-r--r--    1 root     root             4 Jan  4 12:53 docker1-file.txt
-rw-r--r--    1 root     root             4 Jan  4 12:54 docker2-file.txt
-rwxrwxrwx    1 root     root             6 Jan  4 12:51 file.txt
```

8. Exit second container, and return to first container's shell.
```
docker2> exit
```

9. Verify that files are still present using `ls` command
- We expect there that there is `file.txt` from Host file-system.
- And also file `docker1-file.txt` created inside first container.
- And also file `docker2-file.txt` created inside second container.
```
docker1> / # ls -l /app
total 0
-rw-r--r--    1 root     root             4 Jan  4 12:53 docker1-file.txt
-rw-r--r--    1 root     root             4 Jan  4 12:54 docker2-file.txt
-rwxrwxrwx    1 root     root             6 Jan  4 12:51 file.txt
```

10. Exit first container, and return to Host's shell.
```
docker1> exit
```

11. Verify that files are still present using `dir` command
- We expect there that there is `file.txt` from Host file-system.
- And also file `docker1-file.txt` created inside first container.
- And also file `docker2-file.txt` created inside second container.
```
host> dir c:\Temp\my-project-1
04.01.2026  13:53                 4 docker1-file.txt
04.01.2026  13:54                 4 docker2-file.txt
04.01.2026  13:51                 6 file.txt
```

## [FAILED] Mounting WSL created directory

In this example we will use WSL shell (installed default Ubuntu distribution) and assume that we are sharing WSL Host's directory `/tmp/my-project-2`. 

1. We create file in the Host directory.
   - Mind that this directory and file is created in WSL's file-system.
   - It is not created in Windows Host's file-system.
   - We might expect that based on `mkdir /tmp/my-project-2` command,
     - the `c:/tmp/my-project-2` directory might be created -> this assumption is wrong.  
```
host-wsl> mkdir /tmp/my-project-2
host-wsl> echo aaa > /tmp/my-project-2/file.txt
host-wsl> ls -l /tmp/my-project-2
total 4
-rw-r--r-- 1 bedla bedla 4 Jan  4 17:48 file.txt
```

2. We start Docker container `docker:cli` with shell. And we connect it to Host's Docker daemon using `DOCKER_HOST` environment variable.
   - Mind that we are sharing WSL's Host directory `/tmp/my-project-2`. 
```
host-wsl> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v /tmp/my-project-2:/app docker:cli sh
```

3. Verify that files from the Host are properly mounted using `ls` command
  - We expect there that there is `file.txt` from Host file-system.
```
docker1> / # ls -l /app
total 4
-rw-r--r--    1 1000     1000             4 Jan  4 16:48 file.txt
```

4. Create new file `docker1-file.txt` in shared directory, and list files to verify its creation.
```
docker1> / # echo xxx > /app/docker1-file.txt

docker1> / # ls -l /app
total 8
-rw-r--r--    1 root     root             4 Jan  4 16:48 docker1-file.txt
-rw-r--r--    1 1000     1000             4 Jan  4 16:48 file.txt
```

5. Now inside first container, start another container `docker:cli` with shell. We connect it to Host's Docker daemon using `DOCKER_HOST` environment variable.
  - Mind that we mount same directory `/tmp/my-project-2`.
  - We assume that those directories are the same -> we are wrong. 
```
docker1> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v /tmp/my-project-2:/inception docker:cli sh
```

6. Verify that files from host are properly mounted using `ls` command.
  - As you can see we are missing `file.txt` and `docker1-file.txt` files!
```
docker2> / # ls -l /inception
total 0
```

7. Create new file `docker2-file.txt` in shared directory, and list files to verify its creation.
```
docker2> / # echo yyy > /inception/docker2-file.txt

docker2> / # ls -l /inception
total 4
-rw-r--r--    1 root     root             4 Jan  4 16:49 docker2-file.txt
```

8. Exit second container, and return to first container's shell.
```
docker2> exit
```

9. Verify that files are still present using `ls` command
- We expect there that there is `file.txt` from Host file-system.
- And also file `docker1-file.txt` created inside first container.
- BUT we are missing file `docker2-file.txt` created inside second container!
```
docker1> / # ls -l /app
total 8
-rw-r--r--    1 root     root             4 Jan  4 16:48 docker1-file.txt
-rw-r--r--    1 1000     1000             4 Jan  4 16:48 file.txt
```

10. Exit first container, and return to Host's shell.
```
docker1> exit
```

11. Verify that files are still present using `dir` command
- We expect there that there is `file.txt` from Host file-system.
- And also file `docker1-file.txt` created inside first container.
- BUT we are missing file `docker2-file.txt` created inside second container!
```
host-wsl> ls -l /tmp/my-project-2
total 8
-rw-r--r-- 1 root  root  4 Jan  4 17:48 docker1-file.txt
-rw-r--r-- 1 bedla bedla 4 Jan  4 17:48 file.txt
```

12. We can verify same from Windows shell in special `\\wsl.localhost\Ubuntu\tmp\my-project-2\` directory. 
```
host> dir \\wsl.localhost\Ubuntu\tmp\my-project-2\
04.01.2026  17:48                 4 file.txt
04.01.2026  17:48                 4 docker1-file.txt
```

## [FAILED/PARTIALLY-WORKING] Mounting WSL directory originally created at Windows file-system 

In this example we will use WSL shell (installed default Ubuntu distribution).
Directory to share `c:\temp\my-project-3` is created at Windows file-system and shared/mounbted by WSL into `/mnt/c/temp/my-project-3` (standard WSL behavior).

1. We create and verify file in the Host directory
```
host-win> mkdir c:\temp\my-project-3
host-win> echo aaa > c:\temp\my-project-3\file.txt
host-win> dir c:\temp\my-project-3
04.01.2026  18:56                 6 file.txt
```

2. Open WSL terminal and verify file properly auto-mounted/shared.
```
host-wsl> ls -l /mnt/c/temp/my-project-3
total 0
-rwxrwxrwx 1 bedla bedla 6 Jan  4 18:56 file.txt
```

3. Now assume that we are talking to Docker daemon running at Windows filesystem and mount volume using Windows path `c:/temp/my-project-3`.
   - This will rise expected **ERROR**, because malformed (two colons `:` separate mount mode) format for the Docker `run` command running at WSL. 
```
host-wsl> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v c:/temp/my-project-3:/app docker:cli sh

docker: Error response from daemon: invalid mode: /app
Run 'docker run --help' for more information
```

4. Let's fix it by using WSL auto-mounted path `/mnt/c/temp/my-project-3`.
```
host-wsl> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v /mnt/c/temp/my-project-3:/app docker:cli sh
```

5. Verify that files from the Host are properly mounted using `ls` command
- We expect there that there is `file.txt` from Host file-system.
```
docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             6 Jan  4 17:56 file.txt
```

6. Create new file `docker1-file.txt` in shared directory, and list files to verify its creation.
```
docker1> / # echo xxx > /app/docker1-file.txt

docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 17:58 docker1-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 17:56 file.txt
```

7. Now inside first container, start another container `docker:cli` with shell. We connect it to Host's Docker daemon using `DOCKER_HOST` environment variable.
- Mind that we mount same directory `/mnt/c/temp/my-project-3`.
- We assume that those directories are the same -> we are wrong.
```
docker1> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v /mnt/c/temp/my-project-3:/inception docker:cli sh
```

8. Verify that files from host are properly mounted using `ls` command.
- As you can see we are missing `file.txt` and `docker1-file.txt` files!
```
docker2> / # ls -l /inception
total 0
```

9. Create new file `docker2-file.txt` in shared directory, and list files to verify its creation.
```
docker2> / # echo yyy > /inception/docker2-file.txt

docker2> / # ls -l /inception
total 1
-rw-r--r--    1 root     root             4 Jan  4 17:59 docker2-file.txt
```

10. Exit second container, and return to first container's shell.
```
docker2> exit
```

11. Verify that files are still present using `ls` command
- We expect there that there is `file.txt` from Host file-system.
- And also file `docker1-file.txt` created inside first container.
- BUT we are missing file `docker2-file.txt` created inside second container!
```
docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 17:58 docker1-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 17:56 file.txt
```

12. Exit first container, and return to Host's shell.
```
docker1> exit
```

13. Verify that files are still present using `ls` command
- We expect there that there is `file.txt` from Host file-system.
- And also file `docker1-file.txt` created inside first container.
- BUT we are missing file `docker2-file.txt` created inside second container!
```
host-wsl> ls -l /mnt/c/temp/my-project-3
total 0
-rwxrwxrwx 1 bedla bedla 4 Jan  4 18:58 docker1-file.txt
-rwxrwxrwx 1 bedla bedla 6 Jan  4 18:56 file.txt
```

14. What is unexpected is that we can see `mnt\c\temp\my-project-3\` directory created inside Docker WSL VM file-system.
    - And file `docker2-file.txt` from second container created. 
```
host> dir \\wsl.localhost\docker-desktop\mnt\c\temp\my-project-3\
04.01.2026  18:59                 4 docker2-file.txt
```

15a. With this knowledge, let's test if mounting second container to `/mnt/host/c/temp/my-project-3` directory will work.

15b. Start first container and verify files still presented
    - We expect files `docker1-file.txt` and `file.txt` to be presented.
```
host-wsl> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v /mnt/c/temp/my-project-3:/app docker:cli sh

docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 17:58 docker1-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 17:56 file.txt
```

15c. Start second container from frist container, but with modified Host path `/mnt/host/c/temp/my-project-3`
    - Mind that there is prefix `/mnt/host` used.
```
docker1> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v /mnt/host/c/temp/my-project-3:/inception docker:cli sh
```

15d. Verify content of the `/inception` directory and create new test file `docker3-file.txt`.
    - As you can see we are able to see files from the WSL Host -> this is unexpected!
    - Lastly verify that third file has been created using `ls` command.
```
docker2> / # ls -l /inception
total 0
-rwxrwxrwx    1 root     root             4 Jan  4 17:58 docker1-file.txt
-rwxrwxrwx    1 root     root             6 Jan  4 17:56 file.txt

docker2> / # echo yyy > /inception/docker3-file.txt

docker2> / # ls -l /inception
total 0
-rwxrwxrwx    1 root     root             4 Jan  4 17:58 docker1-file.txt
-rw-r--r--    1 root     root             4 Jan  4 18:49 docker3-file.txt
-rwxrwxrwx    1 root     root             6 Jan  4 17:56 file.txt
```

15e. Exit second container, and return to first container's shell.
```
docker2> exit
```

15f. Verify that `docker3-file.txt` file is now shared with first container.
    - Mind that owner is `root` user.
```
docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 17:58 docker1-file.txt
-rw-r--r--    1 root     root             4 Jan  4 18:49 docker3-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 17:56 file.txt
```

15g. Exit first container, and return to Host's shell.
```
docker1> exit
```

15h. Verify that files are still present using `ls` command
    - We expect there that there is `file.txt` from Host file-system.
    - And also file `docker1-file.txt` created inside first container.
    - We are missing file `docker2-file.txt` created inside second container!
    - **BUT we can see file** `docker3-file.txt` created inside third container!
        - Mind that owner is `root` user.
```
host-wsl>  ls -l /mnt/c/temp/my-project-3
total 0
-rwxrwxrwx 1 bedla bedla 4 Jan  4 18:58 docker1-file.txt
-rw-r--r-- 1 root  root  4 Jan  4 19:49 docker3-file.txt
-rwxrwxrwx 1 bedla bedla 6 Jan  4 18:56 file.txt
```

15i. Verify from Windows command line that we can see the files.
```
host> dir c:\temp\my-project-3
04.01.2026  18:58                 4 docker1-file.txt
04.01.2026  19:49                 4 docker3-file.txt
04.01.2026  18:56                 6 file.txt
```

## [FAILED/WORKING] Mounting WSL directory originally created at Windows file-system without `DOCKER_HOST` environment variable

In this example we will use WSL shell (installed default Ubuntu distribution).
Directory to share `c:\temp\my-project-4` is created at Windows file-system and shared/mounbted by WSL into `/mnt/c/temp/my-project-4` (standard WSL behavior).
Based on feedback from Docker support we will try not to use `DOCKER_HOST` environment variable.
Surprise is that when we use Docker socket sharing works.

1. We create and verify file in the Host directory
```
host-win> mkdir c:\temp\my-project-4
host-win> echo aaa > c:\temp\my-project-4\file.txt
host-win> dir c:\temp\my-project-4
04.01.2026  20:09                 6 file.txt
```

2. Open WSL terminal and verify file properly auto-mounted/shared.
```
host-wsl> ls -l /mnt/c/temp/my-project-4
total 0
-rwxrwxrwx 1 bedla bedla 6 Jan  4 20:09 file.txt
```

3. Start container with WSL's auto-mounted path `/mnt/c/temp/my-project-4`.
```
host-wsl> docker run -it -v /mnt/c/temp/my-project-4:/app docker:cli sh
```

4. Verify that files from the Host are properly mounted using `ls` command
- We expect there that there is `file.txt` from Host file-system.
```
docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             6 Jan  4 19:09 file.txt
```

6. Create new file `docker1-file.txt` in shared directory, and list files to verify its creation.
```
docker1> / # echo xxx > /app/docker1-file.txt

docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:10 docker1-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 19:09 file.txt
```

7. Now try to start second container without `DOCKER_HOST` environment variable
   - As we expected we are not able to connect to Host Docker daemon.
   - See all variants with `docker:cli`, `docker`, and `docker:dind` images.
   - Also mind that we tried to start second container with connection to Docker socker at `/var/run/docker.sock`
```
docker1> / # docker run -it -v /mnt/c/temp/my-project-4:/inception docker:cli sh
failed to connect to the docker API at tcp://docker:2375: lookup docker on 192.168.65.7:53: no such host

docker1> / # docker run -it -v /mnt/c/temp/my-project-4:/inception docker sh
failed to connect to the docker API at tcp://docker:2375: lookup docker on 192.168.65.7:53: no such host

docker1> / # docker run -it -v /mnt/c/temp/my-project-4:/inception docker:dind sh
failed to connect to the docker API at tcp://docker:2375: lookup docker on 192.168.65.7:53: no such host

docker1> / # docker run -it -v /mnt/c/temp/my-project-4:/inception -v /var/run/docker.sock:/var/run/docker.sock docker:cli sh
failed to connect to the docker API at tcp://docker:2375: lookup docker on 192.168.65.7:53: no such host

docker1> / # docker run -it -v /mnt/c/temp/my-project-4:/inception -v /var/run/docker.sock:/var/run/docker.sock docker:dind sh
failed to connect to the docker API at tcp://docker:2375: lookup docker on 192.168.65.7:53: no such host

docker1> exit
```

8a. Based on error above let's start from beginning, but now mount Docker host's socket to the first container
    - Option `-v /var/run/docker.sock:/var/run/docker.sock` does the trick
    - With this option Docker commands run inside first containers should be connected to Host's Docker daemon.
```
host-wsl> docker run -it -v /mnt/c/temp/my-project-4:/app -v /var/run/docker.sock:/var/run/docker.sock docker:cli sh
```

8b. Verify mounted files into first container. And create new file `docker3-file.txt` for this freshly started container.  
```
docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:10 docker1-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 19:09 file.txt

docker1> / # echo xxx > /app/docker3-file.txt

docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:10 docker1-file.txt
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:17 docker3-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 19:09 file.txt
```

8c. Start second container
    - We can see that it is started with connection to Docker host's socket
```
docker1> docker run -it -v /mnt/c/temp/my-project-4:/inception docker:cli sh
```

8d. Check if files are properly mounted
    - Surprise is that they are mounted, and we can see them in `/inception` directory. 
```
docker2> / # ls -l /inception
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:10 docker1-file.txt
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:17 docker3-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 19:09 file.txt
```

8e. Create new file `docker4-file.txt` to be shared with host, and exit second container.
```
docker2> / # echo xxx > /inception/docker4-file.txt

docker2> / # ls -l /inception
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:10 docker1-file.txt
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:17 docker3-file.txt
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:18 docker4-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 19:09 file.txt

docker2> exit
```

8f. Verify that we are able to see new second container file `docker4-file`, and exit first container.  
```
docker1> / # ls -l /app
total 0
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:10 docker1-file.txt
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:17 docker3-file.txt
-rwxrwxrwx    1 1000     1000             4 Jan  4 19:18 docker4-file.txt
-rwxrwxrwx    1 1000     1000             6 Jan  4 19:09 file.txt

docker1> exit
```

8g. Verify in WSL terminal that we are able to see all file created inside containers.
```
host-wsl> ls -l /mnt/c/temp/my-project-4
total 0
-rwxrwxrwx 1 bedla bedla 4 Jan  4 20:10 docker1-file.txt
-rwxrwxrwx 1 bedla bedla 4 Jan  4 20:17 docker3-file.txt
-rwxrwxrwx 1 bedla bedla 4 Jan  4 20:18 docker4-file.txt
-rwxrwxrwx 1 bedla bedla 6 Jan  4 20:09 file.txt
```

8h. And same verification from Windows command line.
```
host-win> dir c:\temp\my-project-4
04.01.2026  20:10                 4 docker1-file.txt
04.01.2026  20:17                 4 docker3-file.txt
04.01.2026  20:18                 4 docker4-file.txt
04.01.2026  20:09                 6 file.txt
```

## [WORKING] Using Named Volumes

Based on support feedback we will show how to use Named Volumes to share data between containers. It is not surprise that use-case works perfectly.

_Note: It does not matter if we use WSL terminal or Windows command line._

1. Create Named Volume with name `my-data`.
   - and verify that it was correctly created. 
```
host-wsl> docker volume create my-data
my-data

host-wsl> docker volume inspect my-data
[
    {
        "CreatedAt": "2025-12-18T16:53:46Z",
        "Driver": "local",
        "Labels": null,
        "Mountpoint": "/var/lib/docker/volumes/my-data/_data",
        "Name": "my-data",
        "Options": null,
        "Scope": "local"
    }
]
```
_Note: Mind that data are stored in the `/var/lib/docker/volumes/my-data/_data` directory. BUT this directory is not inside WSL as we will show later._

2. Start First container with Named Volume `my-data` and share it as `/app` directory inside container.
```
host-wsl> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v my-data:/app docker:cli sh
```

3. Check that volume is empty
```
docker1> / # ls -l /app
total 0
```

4. Create test file `/app/docker1-file.txt` and verify that it was created correctly.
```
docker1> / # echo xxx > /app/docker1-file.txt

docker1> / # ls -l /app
total 4
-rw-r--r--    1 root     root             4 Jan  4 19:43 docker1-file.txt
```

5. Start Second container from First container with Named Volume `my-data` and share it as `/inception` directory inside container.
```
docker1> / # docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v my-data:/inception docker:cli sh
```

6. Check that file `docker1-file.txt` from First container is shared with Second container.
```
docker2> / # ls -l /inception
total 4
-rw-r--r--    1 root     root             4 Jan  4 19:43 docker1-file.txt
```

7. Create test file `/app/docker2-file.txt` and verify that it was created correctly. 
```
docker2> / # echo yyy > /inception/docker2-file.txt

docker2> / # ls -l /inception
total 8
-rw-r--r--    1 root     root             4 Jan  4 19:43 docker1-file.txt
-rw-r--r--    1 root     root             4 Jan  4 19:44 docker2-file.txt
```

8. Exit Second container, and return to First container's shell.
```
docker2> exit
```

9. Verify that file from Second container is visible for First container. 
```
docker1> / # ls -l /app
total 8
-rw-r--r--    1 root     root             4 Jan  4 19:43 docker1-file.txt
-rw-r--r--    1 root     root             4 Jan  4 19:44 docker2-file.txt
```

10. Exit first container, and return to Host's shell.
```
docker1> exit
```

11. Try to find Volume data files.
    - We cannot find them in the WSL file-system.
    - And also in the Windows share directory. 
```
host-wsl> ls -l /var/lib/docker/volumes/my-data/_data
ls: cannot access '/var/lib/docker/volumes/my-data/_data': No such file or directory

host> dir \\wsl.localhost\docker-desktop\var\lib\docker\volumes\my-data\_data
The system cannot find the path specified.
```

12. Try to find data files in the Docker Desktop VM file-system.
    - As you can see we are able to find them in 4 places.
    - Which one is source of truth cannot be guessed without deeper knowledge of Docker Desktop itself.
    - But still, it is interesting implementation detail.
```
host> dir \\wsl.localhost\docker-desktop\mnt\docker-desktop-disk\data\docker\volumes\my-data\_data
04.01.2026  20:43                 4 docker1-file.txt
04.01.2026  20:44                 4 docker2-file.txt

dir \\wsl.localhost\docker-desktop\tmp\docker-desktop-root\mnt\docker-desktop-disk\data\docker\volumes\my-data\_data
04.01.2026  20:43                 4 docker1-file.txt
04.01.2026  20:44                 4 docker2-file.txt

dir \\wsl.localhost\docker-desktop\tmp\docker-desktop-root\run\desktop\mnt\docker-desktop-disk\data\docker\volumes\my-data\_data
04.01.2026  20:43                 4 docker1-file.txt
04.01.2026  20:44                 4 docker2-file.txt

dir \\wsl.localhost\docker-desktop\tmp\docker-desktop-root\var\lib\docker\volumes\my-data\_data
04.01.2026  20:43                 4 docker1-file.txt
04.01.2026  20:44                 4 docker2-file.txt
```

## [FAILED] Using WSL terminal with Windows path to start container

This use-case shows how WSL behaves when we use Windows paths for Volume mounts.

1. Create test data as we create them above.
```
host-win> mkdir c:\temp\my-project-5
host-win> echo aaa > c:\temp\my-project-5\file.txt
host-win> dir c:\temp\my-project-5
17.12.2025  20:09                 6 file.txt
host-wsl> ls -l /mnt/c/temp/my-project-5
total 0
-rwxrwxrwx 1 bedla bedla 6 Dec 17 20:09 file.txt
```

2. Create container from WSL with Windows like path `c:\\temp\\my-project-5`.
   - This will rise error talking about invalid `mode`.
```
host-wsl> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v c:\\temp\\my-project-5:/app docker:cli sh

docker: Error response from daemon: invalid mode: /app
Run 'docker run --help' for more information
```

3. Create container from WSL with Windows like path `c:/temp/my-project-5`.
    - This will rise error talking about invalid `mode`.
```
host-wsl> docker run -it -e DOCKER_HOST=tcp://host.docker.internal:2375 -v c:/temp/my-project-5:/app docker:cli sh

docker: Error response from daemon: invalid mode: /app
Run 'docker run --help' for more information
```

- Both error are because in WSL Docker command expect that:
  - Syntax is `docker run -v [<volume-name>:]<mount-path>[:opts]`.
  - Two colons `:` separating options of the Volume mount
  - In our case `mode` is read as `/app` -> invalid value
  - You can see details in the documentation here https://docs.docker.com/engine/storage/volumes/#options-for---volume
- From Windows command line it works, because Docker Desktop is smart enough to know that `c:\` is not colon separator, but beginning of the Windows path.
