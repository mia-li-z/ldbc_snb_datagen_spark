/* 
 Copyright (c) 2013 LDBC
 Linked Data Benchmark Council (http://www.ldbcouncil.org)
 
 This file is part of ldbc_snb_datagen.
 
 ldbc_snb_datagen is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.
 
 ldbc_snb_datagen is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.
 
 You should have received a copy of the GNU General Public License
 along with ldbc_snb_datagen.  If not, see <http://www.gnu.org/licenses/>.
 
 Copyright (C) 2011 OpenLink Software <bdsmt@openlinksw.com>
 All Rights Reserved.
 
 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation;  only Version 2 of the License dated
 June 1991.
 
 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.
 
 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.*/
package ldbc.snb.datagen.hadoop.serializer;

import ldbc.snb.datagen.DatagenContext;
import ldbc.snb.datagen.DatagenMode;
import ldbc.snb.datagen.DatagenParams;
import ldbc.snb.datagen.dictionary.Dictionaries;
import ldbc.snb.datagen.entities.dynamic.person.Person;
import ldbc.snb.datagen.entities.dynamic.relations.Knows;
import ldbc.snb.datagen.hadoop.HadoopBlockMapper;
import ldbc.snb.datagen.hadoop.HadoopBlockPartitioner;
import ldbc.snb.datagen.hadoop.HadoopConfiguration;
import ldbc.snb.datagen.hadoop.HadoopTuplePartitioner;
import ldbc.snb.datagen.hadoop.key.TupleKey;
import ldbc.snb.datagen.hadoop.key.blockkey.BlockKey;
import ldbc.snb.datagen.hadoop.key.blockkey.BlockKeyComparator;
import ldbc.snb.datagen.hadoop.key.blockkey.BlockKeyGroupComparator;
import ldbc.snb.datagen.hadoop.miscjob.HadoopFileRanker;
import ldbc.snb.datagen.hadoop.writer.HdfsCsvWriter;
import ldbc.snb.datagen.serializer.DeleteEventSerializer;
import ldbc.snb.datagen.serializer.DynamicPersonSerializer;
import ldbc.snb.datagen.serializer.InsertEventSerializer;
import ldbc.snb.datagen.util.LdbcConfiguration;
import ldbc.snb.datagen.vocabulary.SN;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;

import java.io.IOException;

public class HadoopPersonSortAndSerializer {

    private Configuration conf;

    public static class HadoopDynamicPersonSerializerReducer extends Reducer<BlockKey, Person, LongWritable, Person> {

        private DynamicPersonSerializer<HdfsCsvWriter> dynamicPersonSerializer;
        private InsertEventSerializer insertEventSerializer;
        private DeleteEventSerializer deleteEventSerializer;

        @Override
        protected void setup(Context context) {
            Configuration hadoopConf = context.getConfiguration();
            LdbcConfiguration conf = HadoopConfiguration.extractLdbcConfig(hadoopConf);
            int reducerId = context.getTaskAttemptID().getTaskID().getId();
            try {
                DatagenContext.initialize(conf);
                dynamicPersonSerializer = HadoopConfiguration.getDynamicPersonSerializer(hadoopConf);
                dynamicPersonSerializer.initialize(hadoopConf, reducerId);
                if (DatagenParams.getDatagenMode() == DatagenMode.INTERACTIVE || DatagenParams.getDatagenMode() == DatagenMode.BI) {
                    insertEventSerializer = new InsertEventSerializer(hadoopConf, HadoopConfiguration.getHadoopDir(hadoopConf) + "/temp_insertStream_person_" + reducerId, reducerId, DatagenParams.numUpdateStreams);
                    deleteEventSerializer = new DeleteEventSerializer(hadoopConf, HadoopConfiguration.getHadoopDir(hadoopConf)  + "/temp_deleteStream_person_" + reducerId, reducerId, DatagenParams.numUpdateStreams);
                }
            } catch (Exception e) {
                System.err.println(e.getMessage());
                throw new RuntimeException(e);
            }
        }

        @Override
        public void reduce(BlockKey key, Iterable<Person> valueSet, Context context) throws IOException {
            for (Person p : valueSet) {

                if (DatagenParams.getDatagenMode() == DatagenMode.RAW_DATA) {
                    dynamicPersonSerializer.export(p);
                    for (Knows k : p.getKnows()) {
                        dynamicPersonSerializer.export(p, k);

                    }
                } else {
                    if ((p.getCreationDate() < Dictionaries.dates.getBulkLoadThreshold() &&
                            (p.getDeletionDate() >= Dictionaries.dates.getBulkLoadThreshold() &&
                                    p.getDeletionDate() <= Dictionaries.dates.getSimulationEnd()))) {
                        dynamicPersonSerializer.export(p);
                        if (p.isExplicitlyDeleted()) {
                            deleteEventSerializer.export(p);
                            deleteEventSerializer.changePartition();
                        }
                    } else if (p.getCreationDate() < Dictionaries.dates.getBulkLoadThreshold()
                            && p.getDeletionDate() > Dictionaries.dates.getSimulationEnd()) {
                        dynamicPersonSerializer.export(p);
                    } else if (p.getCreationDate() >= Dictionaries.dates.getBulkLoadThreshold()
                            && (p.getDeletionDate() >= Dictionaries.dates.getBulkLoadThreshold()) &&
                            p.getDeletionDate() <= Dictionaries.dates.getSimulationEnd()) {
                        insertEventSerializer.export(p);
                        insertEventSerializer.changePartition();
                        if (p.isExplicitlyDeleted()) {
                            deleteEventSerializer.export(p);
                            deleteEventSerializer.changePartition();
                        }
                    } else if (p.getCreationDate() >= Dictionaries.dates.getBulkLoadThreshold()
                            && p.getDeletionDate() > Dictionaries.dates.getSimulationEnd()) {
                        insertEventSerializer.export(p);
                        insertEventSerializer.changePartition();
                    }

                    //TODO: export was split between here and HadoopPersonActivityGenerator, not sure why
                    // moved all here
                    for (Knows k : p.getKnows()) {

                       if ((k.getCreationDate() < Dictionaries.dates.getBulkLoadThreshold() &&
                                (k.getDeletionDate() >= Dictionaries.dates.getBulkLoadThreshold() &&
                                        k.getDeletionDate() <= Dictionaries.dates.getSimulationEnd())
                        )) {
                            dynamicPersonSerializer.export(p, k);
                            if (k.isExplicitlyDeleted()) {
                                deleteEventSerializer.export(p, k);
                                deleteEventSerializer.changePartition();
                            }
                        } else if (k.getCreationDate() < Dictionaries.dates.getBulkLoadThreshold()
                                && k.getDeletionDate() > Dictionaries.dates.getSimulationEnd()
                        ) {
                            dynamicPersonSerializer.export(p, k);
                        } else if (k.getCreationDate() >= Dictionaries.dates.getBulkLoadThreshold()
                                && (k.getDeletionDate() >= Dictionaries.dates.getBulkLoadThreshold()) &&
                                k.getDeletionDate() <= Dictionaries.dates.getSimulationEnd()) {
                            insertEventSerializer.export(p, k);
                            insertEventSerializer.changePartition();
                            if (k.isExplicitlyDeleted()) {
                                deleteEventSerializer.export(p, k);
                                deleteEventSerializer.changePartition();
                            }
                        } else if (k.getCreationDate() >= Dictionaries.dates.getBulkLoadThreshold()
                                && k.getDeletionDate() > Dictionaries.dates.getSimulationEnd()) {
                            insertEventSerializer.export(p, k);
                            insertEventSerializer.changePartition();
                        }

                    }
                }
            }
        }

        @Override
        protected void cleanup(Context context) {
            dynamicPersonSerializer.close();
            if (DatagenParams.getDatagenMode() == DatagenMode.INTERACTIVE || DatagenParams.getDatagenMode() == DatagenMode.BI) {
                try {
                    insertEventSerializer.close();
                    deleteEventSerializer.close();
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage());
                }
            }
        }
    }

    public HadoopPersonSortAndSerializer(Configuration conf) {
        this.conf = new Configuration(conf);
    }

    public void run(String inputFileName) throws Exception {

        FileSystem fs = FileSystem.get(conf);

        String rankedFileName = HadoopConfiguration.getHadoopDir(conf) + "/ranked";
        HadoopFileRanker hadoopFileRanker = new HadoopFileRanker(conf, TupleKey.class, Person.class, null);
        hadoopFileRanker.run(inputFileName, rankedFileName);

        int numThreads = HadoopConfiguration.getNumThreads(conf);
        Job job = Job.getInstance(conf, "Person Serializer");
        job.setMapOutputKeyClass(BlockKey.class);
        job.setMapOutputValueClass(Person.class);
        job.setOutputKeyClass(LongWritable.class);
        job.setOutputValueClass(Person.class);
        job.setJarByClass(HadoopBlockMapper.class);
        job.setMapperClass(HadoopBlockMapper.class);
        job.setReducerClass(HadoopDynamicPersonSerializerReducer.class);
        job.setNumReduceTasks(numThreads);
        job.setInputFormatClass(SequenceFileInputFormat.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.setPartitionerClass(HadoopTuplePartitioner.class);

        job.setSortComparatorClass(BlockKeyComparator.class);
        job.setGroupingComparatorClass(BlockKeyGroupComparator.class);
        job.setPartitionerClass(HadoopBlockPartitioner.class);

        FileInputFormat.setInputPaths(job, new Path(rankedFileName));
        FileOutputFormat.setOutputPath(job, new Path(HadoopConfiguration.getHadoopDir(conf) + "/aux"));
        if (!job.waitForCompletion(true)) {
            throw new IllegalStateException("HadoopPersonSerializer failed");
        }


        try {
            fs.delete(new Path(rankedFileName), true);
            fs.delete(new Path(HadoopConfiguration.getHadoopDir(conf) + "/aux"), true);
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }
}
