#!/bin/bash -eu
# -e: Exit immediately if a command exits with a non-zero status.
# -u: Treat unset variables as an error when substituting.
# 
# Custom backup script that follows conventions for script backups implemented
# by ScriptBackupAgent.  
#

usage() {
  echo "Usage: $0 {-backup|-restore} -properties file [-options opts]"
}

# Parse arguments. 
operation=
options=
properties=

user=root
password=
host=localhost
port=3306
directory=/tmp/innobackup
archive=/tmp/innobackup.tar.gz
mysql_service_command="/etc/init.d/mysql"
mysqldatadir=/var/lib/mysql
mysqluser=mysql
mysqlgroup=mysql

while [ $# -gt 0 ]
do
  case "$1" in
    -backup) 
      operation="backup";;
    -restore) 
      operation="restore";;
    -properties)
      properties="$2"; shift;;
    -options)
      options="$2"; shift;;
    *)  
      echo "unrecognized option: $1"
      usage;
      exit 1;
  esac
  shift
done

#
# Break apart the options and assign them to variables
#
for i in `echo $options | tr '&' '\n'`
do
  parts=(`echo $i | tr '=' '\n'`)
  eval $parts=${parts[1]}
done

if [ ! -f "$mysql_service_command" ];
then
  echo "Unable to determine the service command to start/stop mysql"
  exit 1
fi

# Handle operation. 
if [ "$operation" = "backup" ]; then
  # Echo backup file to properties. 
  echo "file=$archive" > $properties

  # Clean up the filesystem before starting
  rm -rf $directory
  rm -f $archive

  # Copy the database files and apply any pending log entries
  innobackupex-1.5.1 --user=$user --password=$password --host=$host --port=$port --no-timestamp $directory
  innobackupex-1.5.1 --apply-log --user=$user --password=$password --host=$host --port=$port $directory

  # Package up the files and remove the staging directory
  cd $directory
  tar -czf $archive *
  rm -rf $directory

  exit 0
elif [ "$operation" = "restore" ]; then
  # Get the name of the backup file and restore.  
  . $properties

  # Clean up the filesystem before starting
  rm -rf $directory
  mkdir $directory
  cd $directory
  
  # Unpack the backup files
  tar -xzf $file

  # Stop mysql and clear the mysql data directory
  $mysql_service_command stop 1>&2

  # We are expecting the exit code to be 3 so we have to turn off the 
  # error trapping
  set +e
  $mysql_service_command status 1>&2
  if [ $? -ne 3 ]; then
    echo "Unable to properly shutdown the MySQL service"
    exit 1
  fi
  set -e
  
  rm -rf $mysqldatadir/*

  # Copy the backup files to the mysql data directory
  innobackupex-1.5.1 --copy-back $directory

  # Fix the permissions and restart the service
  chown -R $mysqluser:$mysqlgroup $mysqldatadir
  $mysql_service_command start 1>&2

  # Remove the staging directory
  rm -rf $directory
  
  exit 0
else
  echo "Must specify -backup or -restore"
  usage
  exit 1
fi