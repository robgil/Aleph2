package com.ikanow.aleph2.data_import_manager.batch_enrichment.services.mapreduce;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.Tuple3;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentBatchModule;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.EnrichmentControlMetadataBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;
import com.ikanow.aleph2.data_model.utils.ContextUtils;

public class BatchEnrichmentJob{

	public static String BATCH_SIZE_PARAM = "batchSize";
	public static String BE_META_BEAN_PARAM = "metadataName";
	public static String BE_CONTEXT_SIGNATURE = "beContextSignature";

	private static final Logger logger = LogManager.getLogger(BatchEnrichmentJob.class);
	
	public BatchEnrichmentJob(){
		logger.debug("BatchEnrichmentJob constructor");
		
	}
	
	@SuppressWarnings("unused")
	public static class BatchErichmentMapper extends Mapper<String, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>, String, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>>		
	{

		DataBucketBean bucket = null;
		IEnrichmentBatchModule module = null;			
		IEnrichmentModuleContext enrichmentContext = null;
		private int batchSize = 1;
		private BeJobBean beJob = null;;
		private EnrichmentControlMetadataBean ecMetadata = null;
		private SharedLibraryBean beLibrary = null;
			
		
		public BatchErichmentMapper(){
			super();
			System.out.println("BatchErichmentMapper constructor");
		}
		
		@Override
		protected void setup(Mapper<String, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>, String, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>>.Context context) throws IOException, InterruptedException {
			logger.debug("BatchEnrichmentJob setup");
			try{
				
			String contextSignature = context.getConfiguration().get(BE_CONTEXT_SIGNATURE);   
			this.enrichmentContext = ContextUtils.getEnrichmentContext(contextSignature);
			this.bucket = enrichmentContext.getBucket().get();
			this.beLibrary = enrichmentContext.getLibraryConfig();		
			this.ecMetadata = BeJobBean.extractEnrichmentControlMetadata(bucket, context.getConfiguration().get(BE_META_BEAN_PARAM)).get();

			this.batchSize = context.getConfiguration().getInt(BATCH_SIZE_PARAM,1);			
			this.module = (IEnrichmentBatchModule)Class.forName(beLibrary.batch_enrichment_entry_point()).newInstance();
			
			boolean final_stage = true;
			module.onStageInitialize(enrichmentContext, bucket, final_stage);
			}
			catch(Exception e){
				logger.error("Caught Exception",e);
			}

		} // setup

		
		@Override
		protected void map(String key, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>> value,
				Mapper<String, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>, String, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>>.Context context) throws IOException, InterruptedException {
			logger.debug("BatchEnrichmentJob map");
			System.out.println("BatchEnrichmentJob map");
			List<Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>> batch = new ArrayList<Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>>();			
			module.onObjectBatch(batch);
			
		} // map
			
		
	} //BatchErichmentMapper

	public static class BatchEnrichmentReducer extends Reducer<String, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>, String, Tuple3<Long, JsonNode, Optional<ByteArrayOutputStream>>> {

		
	} // reducer



}
