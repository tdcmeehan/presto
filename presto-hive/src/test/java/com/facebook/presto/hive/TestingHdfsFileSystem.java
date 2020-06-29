/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.hive.filesystem.ExtendedFileSystem;
import com.google.common.collect.ImmutableList;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;

import java.net.URI;
import java.util.Iterator;
import java.util.List;

public class TestingHdfsFileSystem
        extends ExtendedFileSystem
{
    private final List<LocatedFileStatus> files;

    public TestingHdfsFileSystem(List<LocatedFileStatus> files)
    {
        this.files = ImmutableList.copyOf(files);
    }

    @Override
    public boolean delete(Path f, boolean recursive)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean rename(Path src, Path dst)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setWorkingDirectory(Path dir)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileStatus[] listStatus(Path f)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public RemoteIterator<LocatedFileStatus> listLocatedStatus(Path f)
    {
        return new RemoteIterator<LocatedFileStatus>()
        {
            private final Iterator<LocatedFileStatus> iterator = files.iterator();

            @Override
            public boolean hasNext()
            {
                return iterator.hasNext();
            }

            @Override
            public LocatedFileStatus next()
            {
                return iterator.next();
            }
        };
    }

    @Override
    public FSDataOutputStream create(
            Path f,
            FsPermission permission,
            boolean overwrite,
            int bufferSize,
            short replication,
            long blockSize,
            Progressable progress)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean mkdirs(Path f, FsPermission permission)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FSDataOutputStream append(Path f, int bufferSize, Progressable progress)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FSDataInputStream open(Path f, int bufferSize)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileStatus getFileStatus(Path f)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Path getWorkingDirectory()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI getUri()
    {
        throw new UnsupportedOperationException();
    }
}
