/*******************************************************************************
* Copyright 2015, The IKANOW Open Source Project.
* 
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License, version 3,
* as published by the Free Software Foundation.
* 
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
* 
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
******************************************************************************/
package com.ikanow.aleph2.data_import_manager.batch_enrichment.services;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.ikanow.aleph2.data_import.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService;
import com.ikanow.aleph2.data_model.objects.data_import.AnnotationBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketStatusBean;
import com.ikanow.aleph2.data_model.objects.shared.AssetStateDirectoryBean.StateDirectoryType;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils.BeanTemplate;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.data_model.utils.PropertiesUtils;
import com.ikanow.aleph2.data_model.utils.SetOnce;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.distributed_services.data_model.DistributedServicesPropertyBean;
import com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;

import fj.data.Either;

/** The implementation of the batch  enrichment context
 * @author Joern
 */
public class BatchEnrichmentContext implements IEnrichmentModuleContext {

	static final Logger _logger = LogManager.getLogger(BatchEnrichmentContext.class); 

	////////////////////////////////////////////////////////////////
	
	// CONSTRUCTION
	
	public static final String __MY_BUCKET_ID = "12fc53ed-44cc-4777-9251-d7347480efb2";	
	public static final String __MY_LIBRARY_ID = "6b5a32e4-c457-4d37-94e7-3fce8f773be2";
	
	protected static class MutableState {
		//TODO (ALEPH-10) logging information - will be genuinely mutable
		SetOnce<DataBucketBean> bucket = new SetOnce<DataBucketBean>();
		SetOnce<SharedLibraryBean> library_config = new SetOnce<>();
		SetOnce<String> user_topology_entry_point = new SetOnce<>();
		final SetOnce<ImmutableSet<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>> service_manifest_override = new SetOnce<>();
		final SetOnce<String> signature_override = new SetOnce<>();		
	};	
	protected final MutableState _mutable_state = new MutableState(); 
	
	public enum State { IN_TECHNOLOGY, IN_MODULE };
	protected final State _state_name;	
	
	// (stick this injection in and then call injectMembers in IN_MODULE case)
	@Inject protected IServiceContext _service_context;	
	protected IManagementDbService _core_management_db;
	protected ICoreDistributedServices _distributed_services; 	
	protected ISearchIndexService _index_service;
	protected GlobalPropertiesBean _globals;

	// For writing objects out
	protected Optional<IDataWriteService<JsonNode>> _crud_index_service;
	protected Optional<IDataWriteService.IBatchSubservice<JsonNode>> _batch_index_service;
	
	private static ConcurrentHashMap<String, BatchEnrichmentContext> static_instances = new ConcurrentHashMap<>();
	
	/**Guice injector
	 * @param service_context
	 */
	@Inject 
	public BatchEnrichmentContext(final IServiceContext service_context) {
		_state_name = State.IN_TECHNOLOGY;
		_service_context = service_context;
		_core_management_db = service_context.getCoreManagementDbService(); // (actually returns the _core_ management db service)
		_distributed_services = service_context.getService(ICoreDistributedServices.class, Optional.empty()).get();		
		_index_service = service_context.getService(ISearchIndexService.class, Optional.empty()).get();
		_globals = service_context.getGlobalProperties();
	}

	/** In-module constructor
	 */
	public BatchEnrichmentContext() {
		_state_name = State.IN_MODULE;
		
		// Can't do anything until initializeNewContext is called
	}	
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the bucket for this harvest context instance
	 * @param this_bucket - the bucket to associate
	 * @returns whether the bucket has been updated (ie fails if it's already been set)
	 */
	public boolean setBucket(DataBucketBean this_bucket) {
		return _mutable_state.bucket.set(this_bucket);
	}
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the library bean for this harvest context instance
	 * @param this_bucket - the library bean to be associated
	 * @returns whether the library bean has been updated (ie fails if it's already been set)
	 */
	public boolean setLibraryConfig(SharedLibraryBean lib_config) {
		return _mutable_state.library_config.set(lib_config);
	}
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the user topology entry point for this harvest context instance
	 * @param this_bucket - the user entry point to associate
	 * @returns whether the user entry point has been updated (ie fails if it's already been set)
	 */
	public boolean setUserTopologyEntryPoint(final String entry_point) {
		return _mutable_state.user_topology_entry_point.set(entry_point);
	}
	
	/** FOR DEBUGGING AND TESTING ONLY, inserts a copy of the current context into the saved "in module" versions
	 */
	public void overrideSavedContext() {
		static_instances.put(_mutable_state.signature_override.get().split(":", 2)[1], this);
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#initializeNewContext(java.lang.String)
	 */
	@Override
	public void initializeNewContext(final String signature) {
		try {
			// Inject dependencies
			final Config parsed_config = ConfigFactory.parseString(signature);
			final BatchEnrichmentContext to_clone = static_instances.get(signature);
			
			if (null != to_clone) { //copy the fields				
				_service_context = to_clone._service_context;
				_core_management_db = to_clone._core_management_db;
				_distributed_services = to_clone._distributed_services;	
				_index_service = to_clone._index_service;
				_globals = to_clone._globals;
				// (apart from bucket, which is handled below, rest of mutable state is not needed)
			}
			else {				
				ModuleUtils.initializeApplication(Collections.emptyList(), Optional.of(parsed_config), Either.right(this));

				_core_management_db = _service_context.getCoreManagementDbService(); // (actually returns the _core_ management db service)
				_distributed_services = _service_context.getService(ICoreDistributedServices.class, Optional.empty()).get();
				_index_service = _service_context.getService(ISearchIndexService.class, Optional.empty()).get();
				_globals = _service_context.getGlobalProperties();
			}			
			// Get bucket 

			final BeanTemplate<DataBucketBean> retrieve_bucket = BeanTemplateUtils.from(parsed_config.getString(__MY_BUCKET_ID), DataBucketBean.class);
			_mutable_state.bucket.set(retrieve_bucket.get());
			final BeanTemplate<SharedLibraryBean> retrieve_library = BeanTemplateUtils.from(parsed_config.getString(__MY_LIBRARY_ID), SharedLibraryBean.class);
			_mutable_state.library_config.set(retrieve_library.get());
			
			_batch_index_service = 
					(_crud_index_service = _index_service.getDataService()
												.flatMap(s -> s.getWritableDataService(JsonNode.class, retrieve_bucket.get(), Optional.empty(), Optional.empty()))
					)
					.flatMap(IDataWriteService::getBatchWriteSubservice)
					.map(x -> (ICrudService.IBatchSubservice<JsonNode>) x);

			static_instances.put(signature, this);
		}
		catch (Exception e) {
			//DEBUG
			//System.out.println(ErrorUtils.getLongForm("{0}", e));			

			throw new RuntimeException(e);
		}
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getEnrichmentContextSignature(java.util.Optional)
	 */
	@Override
	public String getEnrichmentContextSignature(final Optional<DataBucketBean> bucket, final Optional<Set<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>> services) {
		if (_state_name == State.IN_TECHNOLOGY) {
			// Returns a config object containing:
			// - set up for any of the services described
			// - all the rest of the configuration
			// - the bucket bean ID
			
			final Config full_config = ModuleUtils.getStaticConfig()
										.withoutPath(DistributedServicesPropertyBean.APPLICATION_NAME)
										.withoutPath("MongoDbManagementDbService.v1_enabled") // (special workaround for V1 sync service)
										;
	
			final Optional<Config> service_config = PropertiesUtils.getSubConfig(full_config, "service");
			
			final ImmutableSet<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>> complete_services_set = 
					ImmutableSet.<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>builder()
							.addAll(services.orElse(Collections.emptySet()))
							.add(Tuples._2T(ICoreDistributedServices.class, Optional.empty()))
							.add(Tuples._2T(IManagementDbService.class, Optional.empty()))
							.add(Tuples._2T(ISearchIndexService.class, Optional.empty()))
							.add(Tuples._2T(ISecurityService.class, Optional.empty()))
							.add(Tuples._2T(IStorageService.class, Optional.empty()))
							.add(Tuples._2T(IManagementDbService.class, IManagementDbService.CORE_MANAGEMENT_DB))
							.build();
			
			if (_mutable_state.service_manifest_override.isSet()) {
				if (!complete_services_set.equals(_mutable_state.service_manifest_override.get())) {
					throw new RuntimeException(ErrorUtils.SERVICE_RESTRICTIONS);
				}
			}
			else {
				_mutable_state.service_manifest_override.set(complete_services_set);
			}
			
			final Config config_no_services = full_config.withoutPath("service");
			
			// Ugh need to add: core deps, core + underlying management db to this list
			
			final Config service_subset = complete_services_set.stream() // DON'T MAKE PARALLEL SEE BELOW
				.map(clazz_name -> {
					final String config_path = clazz_name._2().orElse(clazz_name._1().getSimpleName().substring(1));
					return service_config.get().hasPath(config_path) 
							? Tuples._2T(config_path, service_config.get().getConfig(config_path)) 
							: null;
				})
				.filter(cfg -> null != cfg)
				.reduce(
						ConfigFactory.empty(),
						(acc, k_v) -> acc.withValue(k_v._1(), k_v._2().root()),
						(acc1, acc2) -> acc1 // (This will never be called as long as the above stream is not parallel)
						);
				
			final Config config_subset_services = config_no_services.withValue("service", service_subset.root());
			
			final Config last_call = config_subset_services
								.withValue(__MY_BUCKET_ID, 
											ConfigValueFactory
												.fromAnyRef(BeanTemplateUtils.toJson(bucket.orElseGet(() -> _mutable_state.bucket.get())).toString())
												)
								.withValue(__MY_LIBRARY_ID, 
											ConfigValueFactory
												.fromAnyRef(BeanTemplateUtils.toJson(_mutable_state.library_config.get()).toString())
												)
												;
			
			final String ret = this.getClass().getName() + ":" + last_call.root().render(ConfigRenderOptions.concise());
			_mutable_state.signature_override.set(ret);

			return ret;
		}
		else {
			throw new RuntimeException(ErrorUtils.TECHNOLOGY_NOT_MODULE);			
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingArtefacts()
	 */
	@Override
	public Collection<Object> getUnderlyingArtefacts() {
		if (_state_name == State.IN_TECHNOLOGY) {
			if (!_mutable_state.service_manifest_override.isSet()) {
				throw new RuntimeException(ErrorUtils.SERVICE_RESTRICTIONS);				
			}
			return Stream.concat(
				Stream.of(this, _service_context)
				,
				_mutable_state.service_manifest_override.get().stream()
					.map(t2 -> _service_context.getService(t2._1(), t2._2()))
					.filter(service -> service.isPresent())
					.flatMap(service -> service.get().getUnderlyingArtefacts().stream())
			)
			.collect(Collectors.toList());
		}
		else {
			throw new RuntimeException(ErrorUtils.TECHNOLOGY_NOT_MODULE);			
		}
	}

	////////////////////////////////////////////////////////////////
	
	// OVERRIDES
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getTopologyEntryPoints(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <T> Collection<Tuple2<T, String>> getTopologyEntryPoints(final Class<T> clazz, final Optional<DataBucketBean> bucket) {
		throw new RuntimeException(ErrorUtils.NOT_SUPPORTED_IN_BATCH_ENRICHMENT);						
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getTopologyStorageEndpoint(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <T> T getTopologyStorageEndpoint(final Class<T> clazz, final Optional<DataBucketBean> bucket) {
			throw new RuntimeException(ErrorUtils.NOT_SUPPORTED_IN_BATCH_ENRICHMENT);						
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getTopologyErrorEndpoint(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <T> T getTopologyErrorEndpoint(final Class<T> clazz, final Optional<DataBucketBean> bucket) {
		throw new RuntimeException(ErrorUtils.NOT_SUPPORTED_IN_BATCH_ENRICHMENT);						
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#convertToMutable(com.fasterxml.jackson.databind.JsonNode)
	 */
	@Override
	public ObjectNode convertToMutable(final JsonNode original) {
		return (ObjectNode) original;
	}

	@Override
	public void emitMutableObject(final long id, final ObjectNode mutated_json, final Optional<AnnotationBean> annotation) {
		if (annotation.isPresent()) {
			throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);			
		}
		//TODO move this to output format based on library configuration
		if (_batch_index_service.isPresent()) {
			_batch_index_service.get().storeObject(mutated_json);
		}
		else if (_crud_index_service.isPresent()){ // (super slow)
			_crud_index_service.get().storeObject(mutated_json);
		}
		//TODO write one or write multiple?
		//_hadoopContext.context.write(key, value);

		//(else nothing to do)
	}

	@Override
	public void emitImmutableObject(final long id, final JsonNode original_json, final Optional<ObjectNode> mutations, final Optional<AnnotationBean> annotations)
	{
		if (annotations.isPresent()) {
			throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);			
		}
		final JsonNode to_emit = 
				mutations.map(o -> StreamSupport.<Map.Entry<String, JsonNode>>stream(Spliterators.spliteratorUnknownSize(o.fields(), Spliterator.ORDERED), false)
									.reduce(original_json, (acc, kv) -> ((ObjectNode) acc).set(kv.getKey(), kv.getValue()), (val1, val2) -> val2))
									.orElse(original_json);
		
		emitMutableObject(0L, (ObjectNode)to_emit, annotations);
	}

	@Override
	public void storeErroredObject(final long id, final JsonNode original_json) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	@Override
	public long getNextUnusedId() {
		//TODO
		return 0;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getService(java.lang.Class, java.util.Optional)
	 */
	@Override
	public IServiceContext getServiceContext() {
		return _service_context;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getBucket()
	 */
	@Override
	public Optional<DataBucketBean> getBucket() {
		return _mutable_state.bucket.isSet() ? Optional.of(_mutable_state.bucket.get()) : Optional.empty();
	}
	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getBucketStatus(java.util.Optional)
	 */
	@Override
	public CompletableFuture<DataBucketStatusBean> getBucketStatus(
			final Optional<DataBucketBean> bucket) {
		return this._core_management_db
				.readOnlyVersion()
				.getDataBucketStatusStore()
				.getObjectById(bucket.orElseGet(() -> _mutable_state.bucket.get())._id())
				.thenApply(opt_status -> opt_status.get());		
		// (ie will exception if not present)
	}

	@Override
	public void logStatusForBucketOwner(final Optional<DataBucketBean> bucket, final BasicMessageBean message, final boolean roll_up_duplicates) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	@Override
	public void logStatusForBucketOwner(final Optional<DataBucketBean> bucket, final BasicMessageBean message) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	@Override
	public void emergencyDisableBucket(final Optional<DataBucketBean> bucket) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	@Override
	public void emergencyQuarantineBucket(final Optional<DataBucketBean> bucket, final String quarantine_duration) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(Class<T> driver_class, Optional<String> driver_options) {
		return Optional.empty();
	}
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IHarvestContext#getLibraryConfig()
	 */
	@Override
	public SharedLibraryBean getModuleConfig() {
		return _mutable_state.library_config.get();
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getGlobalEnrichmentModuleObjectStore(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <S> ICrudService<S> getGlobalEnrichmentModuleObjectStore(
			Class<S> clazz, Optional<String> collection) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getBucketObjectStore(java.lang.Class, java.util.Optional, java.util.Optional, java.util.Optional)
	 */
	@Override
	public <S> ICrudService<S> getBucketObjectStore(Class<S> clazz,
			Optional<DataBucketBean> bucket, Optional<String> collection,
			Optional<StateDirectoryType> type) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}
}
