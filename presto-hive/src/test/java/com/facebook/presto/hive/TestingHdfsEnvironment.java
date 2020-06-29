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

import com.facebook.presto.hive.authentication.NoHdfsAuthentication;
import com.facebook.presto.hive.filesystem.ExtendedFileSystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;

import java.util.List;

public class TestingHdfsEnvironment
        extends HdfsEnvironment
{
    private final List<LocatedFileStatus> files;

    public TestingHdfsEnvironment(List<LocatedFileStatus> files)
    {
        super(
                new HiveHdfsConfiguration(new HdfsConfigurationInitializer(new HiveClientConfig(), new MetastoreClientConfig()), ImmutableSet.of()),
                new MetastoreClientConfig(),
                new NoHdfsAuthentication());
        this.files = ImmutableList.copyOf(files);
    }

    @Override
    public ExtendedFileSystem getFileSystem(String user, Path path, Configuration configuration)
    {
        return new TestingHdfsFileSystem(files);
    }
}
