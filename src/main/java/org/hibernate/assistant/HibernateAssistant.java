package org.hibernate.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.Internal;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.assistant.rag.HibernateContentRetriever;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.util.EntityPrinter;
import org.hibernate.metamodel.model.domain.JpaMetamodel;
import org.hibernate.metamodel.model.domain.ManagedDomainType;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.query.spi.AbstractSelectionQuery;
import org.hibernate.type.descriptor.java.JavaType;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.MapAttribute;
import jakarta.persistence.metamodel.MappedSuperclassType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;

import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static dev.langchain4j.model.chat.request.ResponseFormatType.JSON;
import static org.hibernate.assistant.rag.HibernateContentRetriever.INJECTOR_PROMPT_TEMPLATE;

/**
 * Hibernate interface that allows interacting with an LLM through LangChain4J.
 * <p>
 * It is highly recommended to use a {@link ChatLanguageModel} that supports
 * <a href="https://docs.langchain4j.dev/tutorials/structured-outputs#json-schema">JSON Schema</a>
 * to improve the chances of extracting a valid query from the LLM's messages. Note that
 * this requires to enable <a href="https://docs.langchain4j.dev/tutorials/ai-services/#json-mode">JSON mode</a>
 * on the provided chat model.
 */
public class HibernateAssistant {
	private static final Logger log = Logger.getLogger( HibernateAssistant.class );

	private static final PromptTemplate METAMODEL_PROMPT_TEMPLATE = PromptTemplate.from(
			"""
					You are an expert in writing Hibernate Query Language (HQL) queries.
					You have access to a entity model with the following structure:
					
					{{it}}
					
					If a user asks a question that can be answered by querying this model, generate an HQL SELECT query.
					The query must not include any input parameters.
					Do not output anything else aside from a valid HQL statement!
					""" );

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {
		private ChatLanguageModel chatModel;
		private ChatMemory chatMemory;
		private Metamodel metamodel;
		private PromptTemplate metamodelPromptTemplate;
		private boolean structuredJson = true;

		private Builder() {
		}

		public Builder chatModel(ChatLanguageModel chatModel) {
			this.chatModel = chatModel;
			return this;
		}

		public Builder chatMemory(ChatMemory chatMemory) {
			this.chatMemory = chatMemory;
			return this;
		}

		public Builder metamodel(Metamodel metamodel) {
			this.metamodel = metamodel;
			return this;
		}

		public Builder structuredJson(boolean structuredJson) {
			this.structuredJson = structuredJson;
			return this;
		}

		/**
		 * The initial {@link SystemMessage} to instruct the language model about creating HQL queries,
		 * and the structure of the domain metamodel (mapped classes and their structure).
		 * <p>
		 * Must include {@code {{it}}} where the generated mapped objects information will be substituted.
		 * Defaults to {@link #METAMODEL_PROMPT_TEMPLATE}.
		 *
		 * @param metamodelPromptTemplate the custom prompt template to be used
		 *
		 * @return {{@code this}} for chaining calls
		 */
		public Builder metamodelPromptTemplate(PromptTemplate metamodelPromptTemplate) {
			this.metamodelPromptTemplate = metamodelPromptTemplate;
			return this;
		}

		public HibernateAssistant build() {
			return new HibernateAssistant( this );
		}

		private static ChatMemory defaultChatMemory() {
			// this can be tweaked, but really should be user-provided
			return MessageWindowChatMemory.withMaxMessages( 10 );
		}
	}

	// todo : construct a natural-language response with the data obtained from HQL
	// todo : "AI-query" object holding the HQL, with the possibility of accessing it instead of executing
	// todo : look into LC4J RetrievalAugmentor APIs and the possibility of providing an extension there

	// todo : another alternative can be generating ddl (with Hibernate's SchemaManager),
	//  and creating plain SQL queries (might be better in some contexts, but less safe)

	// todo (less important) : chat memory can be made persistent (instead of storing in-memory) through an entity mapping

	//	private final AiQueryService service;
	private final ChatLanguageModel chatModel;
	private final SystemMessage metamodelPrompt;
	private final ChatMemory chatMemory;
	private final JpaMetamodel metamodel;
	private final boolean structuredJson;

	private HibernateAssistant(
			ChatLanguageModel chatModel,
			ChatMemory chatMemory,
			Metamodel metamodel,
			PromptTemplate metamodelPromptTemplate,
			boolean structuredJson) {
		this.chatModel = chatModel;
		this.chatMemory = chatMemory;
		this.metamodel = (JpaMetamodel) metamodel;
		this.structuredJson = structuredJson;

		this.metamodelPrompt = getMetamodelPrompt( metamodelPromptTemplate, metamodel );
		log.infof( "Metamodel prompt: %s", metamodelPrompt.text() );
		chatMemory.add( metamodelPrompt );

//		AiServices.builder( AiQueryService.class )
//				.chatLanguageModel( chatModel )
//				.chatMemory( chatMemory )
//				.build();

//		this.service = AiServices.create( AiQueryService.class, chatModel );
//		service.chat( metamodelPrompt );
	}

	private HibernateAssistant(Builder builder) {
		this(
				ensureNotNull( builder.chatModel, "ChatLanguageModel" ),
				getOrDefault( builder.chatMemory, Builder::defaultChatMemory ),
				ensureNotNull( builder.metamodel, "Metamodel" ),
				getOrDefault( builder.metamodelPromptTemplate, METAMODEL_PROMPT_TEMPLATE ),
				builder.structuredJson
		);
	}

	private static SystemMessage getMetamodelPrompt(PromptTemplate metamodelPromptTemplate, Metamodel metamodel) {
		return metamodelPromptTemplate.apply( getDomainModelPrompt( metamodel ) ).toSystemMessage();
	}

	/**
	 * Reset the assistant's {@link ChatMemory} to return to a clean state. This should be done each
	 * time you create a new {@link AiQuery}, unless you're relying on the context of previous
	 * requests to formulate your current question.
	 */
	public void clearMemory() {
		this.chatMemory.clear();
		this.chatMemory.add( metamodelPrompt );
	}

	public AiQuery<Object> createAiQuery(String message, Session session) {
		return createAiQuery( message, session, Object.class );
	}

	public <T> AiQuery<T> createAiQuery(String message, Session session, Class<T> resultClass) {
		final ManagedDomainType<T> managedType = resultClass != Object.class && !resultClass.isInterface() ?
				metamodel.findManagedType( resultClass ) :
				null;
		if ( managedType != null ) {
			message += "\nThe query must return objects of type \"" + managedType.getTypeName() + "\".";
		}

		final UserMessage userMessage = UserMessage.from( message );
		chatMemory.add( userMessage );

		log.infof( "User message: %s", message );

//		final AiQuery aiQuery = service.chat( message );

		final ChatRequest.Builder requestBuilder = ChatRequest.builder().messages( chatMemory.messages() );
		if ( structuredJson ) {
			requestBuilder.responseFormat( hqlResponseFormat() );
		}

		final ChatRequest chatRequest = requestBuilder.build();

		final ChatResponse chatResponse = chatModel.chat( chatRequest );

		final String hql = extractHql( chatResponse, structuredJson );

		log.infof( "Extracted HQL: %s", hql );

		return AiQuery.from( hql, resultClass, session );
	}

	private static String extractHql(ChatResponse chatResponse, boolean structuredJson) {
		final String response = chatResponse.aiMessage().text();

		log.infof( "Raw model response: %s", response );

		if ( structuredJson ) {
			final HqlHolder hqlHolder;
			try {
				hqlHolder = new ObjectMapper().readValue( response, HqlHolder.class );
			}
			catch (JsonProcessingException e) {
				throw new RuntimeException( e );
			}

			return hqlHolder.hqlQuery();
		}
		else {
			return extractHql( response );
		}
	}

	public static String extractHql(String response) {
		// Try our best to extract valid HQL from text
		final String regex = "(?i)\\bSELECT\\b.*?(?:;|\\n|$)";
		final Pattern pattern = Pattern.compile( regex );
		final Matcher matcher = pattern.matcher( response );
		if ( matcher.find() ) {
			return matcher.group().trim();
		}
		return null;
	}

	/**
	 * Calls the {@link ChatLanguageModel} with the provided message and tries to answer it with
	 * data from the current domain metamodel. You can also use this retrieval-augmented generation
	 * yourself with the {@link org.hibernate.assistant.rag.HibernateContentRetriever} that
	 * directly interfaces with LangChain4J's {@link dev.langchain4j.rag.RetrievalAugmentor} APIs.
	 * <p>
	 * Note that this requires the assistant's {@link ChatMemory} to be able to store at least 3 messages:
	 * the base mapping model system message, the initial request to create the query and the String
	 * representation of the query results.
	 *
	 * @param message the natural language request
	 * @param sessionFactory Hibernate's session factory
	 *
	 * @return a natural language response based on the results of the query
	 */
	public String executeQuery(String message, SessionFactory sessionFactory) {
		final HibernateContentRetriever contentRetriever = new HibernateContentRetriever(
				this,
				sessionFactory
		);

		final RetrievalAugmentor rag = DefaultRetrievalAugmentor.builder()
				.contentRetriever( contentRetriever )
				.contentInjector( DefaultContentInjector.builder().promptTemplate( INJECTOR_PROMPT_TEMPLATE ).build() )
				.build();
		final ConversationalRetrievalChain chain = ConversationalRetrievalChain.builder()
				.chatLanguageModel( chatModel )
				.chatMemory( chatMemory )
				.retrievalAugmentor( rag )
				.build();

		return chain.execute( message );
	}

	/**
	 * Executes the given {@link AiQuery} as a {@link org.hibernate.query.SelectionQuery}, and provides
	 * a natural language response by giving the results back to the {@link ChatLanguageModel}.
	 * <p>
	 * Note that this requires the assistant's {@link ChatMemory} to be able to store at least 3 messages:
	 * the base mapping model system message, the initial request to create the query and the String
	 * representation of the query results.
	 * <p>
	 * To directly obtain a natural language response from a natural language request,
	 * you can use {@link #executeQuery(String, SessionFactory)} instead.
	 * <p>
	 * If you wish to execute the query directly and obtain the results yourself, you should use
	 * {@link AiQuery#createSelectionQuery()} or {@link AiQuery#getResultList()}.
	 *
	 * @param query the AI query to execute
	 * @param session the session in which to execute the query
	 *
	 * @return a natural language response based on the results of the query
	 */
	public String executeQuery(AiQuery<?> query, Session session) {
		final String result = executeQueryToString( query, session );

		final String prompt = "The query returned the following data:\n" + result +
				// this seems to be needed, otherwise with some models we just get an HQL query
				"\nAnswer the original question using natural language and do not create a query!";

		log.infof( "Query result prompt: %s", prompt );

		final UserMessage userMessage = UserMessage.from( prompt );
		chatMemory.add( userMessage );

		final ChatRequest chatRequest = ChatRequest.builder()
				.messages( chatMemory.messages() )
				.build();

		final ChatResponse chatResponse = chatModel.chat( chatRequest );
		return chatResponse.aiMessage().text();
	}

	/**
	 * Executes the given {@link AiQuery} as a {@link org.hibernate.query.SelectionQuery}, and provides
	 * a string representation of the response. The string will be created based on Hibernate's
	 * knowledge of the domain model, but it will not print the entire object tree since that
	 * would cause circularity problems. This is a best-effort attempt at providing a useful
	 * string-representation based on data, mainly used to pass it back to a {@link ChatLanguageModel}
	 * like in {@link #executeQuery(AiQuery, Session)}.
	 * <p>
	 * If you wish to execute the query directly and obtain the results yourself, you should use
	 * {@link AiQuery#createSelectionQuery()} or {@link AiQuery#getResultList()}.
	 *
	 * @param query the AI query to execute
	 * @param session the session in which to execute the query
	 *
	 * @return a natural language response based on the results of the query
	 */
	public String executeQueryToString(AiQuery<?> query, Session session) {
		final AbstractSelectionQuery<?> selectionQuery = (AbstractSelectionQuery<?>) query.createSelectionQuery();

		final List<?> resultList = selectionQuery.getResultList();
		if ( resultList.isEmpty() ) {
			return "The query did not return any results.";
		}

		final List<String> resultRows = new ArrayList<>();

		// header
		final Class<?> resultType = selectionQuery.getResultType();
//		if ( resultType != null && resultType != Object.class ) {
//			resultRows.add( "The query returned objects of type: \"" + resultType.getTypeName() + "\"." );
//		}
//		else {
//			resultRows.add( "The query returned the following objects." );
//		}

//		final ManagedDomainType<?> managedType = sessionFactory.getJpaMetamodel().findManagedType( resultType );
//		if ( managedType != null ) {
//			resultRows.add( String.join(
//					",",
//					managedType.getAttributes().stream().map( Attribute::getName ).toList()
//			) );
//		}

		// contents
		resultList.forEach( result -> resultRows.add( serializeToString(
				result,
				resultType,
				(SessionFactoryImplementor) session.getSessionFactory()
		) ) );


		return String.join( "\n", resultRows );
	}

	/**
	 * Tries to get a meaningful String representation of the result of an HQL query.
	 * We use {@link EntityPrinter} for entities, this allows us to handle associations
	 * cleanly, but it doesn't print the whole object tree - that would pose a problem
	 * of circularity, so we'd have to explore options to handle that.
	 */
	private static String serializeToString(Object result, Class<?> resultType, SessionFactoryImplementor sf) {
		if ( result == null ) {
			return "<null>";
		}

		if ( resultType != null && resultType != Object.class ) {
			if ( resultType.isArray() ) {
				Object[] array = (Object[]) result;
				List<String> results = new ArrayList<>( array.length );
				for ( Object r : array ) {
					results.add( serializeToString( r, r == null ? null : r.getClass(), sf ) );
				}
				return "[" + String.join( ",", results ) + "]";
			}
			else {
				final EntityPersister entityDescriptor = sf.getMappingMetamodel().findEntityDescriptor(
						resultType
				);
				if ( entityDescriptor != null ) {
					return new EntityPrinter( sf ).toString( entityDescriptor.getEntityName(), result );
				}
				else {
					// try to resolve based on Hibernate's knowledge of the type
					final JavaType<Object> descriptor = sf.getTypeConfiguration()
							.getJavaTypeRegistry()
							.getDescriptor( resultType );
					if ( descriptor != null ) {
						// todo special handling for embeddables (we'd need the navigable role) ?
						// todo special handling for mapped-superclasses ?
						return descriptor.toString( result );
					}
				}
			}
		}

		// As a last stand, just rely on the object's toString() method
		return result.toString();
	}

	public <T> ManagedType<T> findManagedType(Class<T> type) {
		return metamodel.findManagedType( type );
	}

	@Internal
	public JpaMetamodel getMetamodel() {
		return metamodel;
	}

	record HqlHolder(String hqlQuery) {
	}

	interface AiQueryService {
		HqlHolder chat(String message);
	}

	private static ResponseFormat hqlResponseFormat() {
		return ResponseFormat.builder().type( JSON ) // type can be either TEXT (default) or JSON
				.jsonSchema( JsonSchema.builder().name( "HQL" ) // OpenAI requires specifying the name for the schema
									 .rootElement( JsonObjectSchema.builder() // see [1] below
														   .addStringProperty( "hqlQuery" )
														   .required( "hqlQuery" ) // see [2] below
														   .build() ).build() ).build();
	}

	public static String getDomainModelPrompt(Metamodel metamodel) {
		final StringBuilder sb = new StringBuilder();
		for ( ManagedType<?> managedType : metamodel.getManagedTypes() ) {
			final String typeDescription = switch ( managedType.getPersistenceType() ) {
				case ENTITY -> getEntityTypeDescription( (EntityType<?>) managedType );
				case EMBEDDABLE -> getEmbeddableTypeDescription( (EmbeddableType<?>) managedType );
				case MAPPED_SUPERCLASS -> getMappedSuperclassTypeDescription( (MappedSuperclassType<?>) managedType );
				default ->
						throw new IllegalStateException( "Unexpected persistence type for managed type [" + managedType + "]" );
			};
			sb.append( typeDescription ).append( "\n" );
		}
		return sb.toString();
	}

	private static <T> String getEntityTypeDescription(EntityType<T> entityType) {
		return "\"" + entityType.getName() + "\" is an entity type.\n" + getJavaTypeDescription( entityType ) + getInheritanceDescription(
				(ManagedDomainType<?>) entityType ) + getIdentifierDescription( entityType ) + getAttributesDescription(
				entityType.getAttributes() );
	}

	private static String getJavaTypeDescription(ManagedType<?> managedType) {
		return "It corresponds to the java class \"" + managedType.getJavaType().getTypeName() + "\"\n";
	}

	private static String getInheritanceDescription(ManagedDomainType<?> managedType) {
		final ManagedDomainType<?> superType = managedType.getSuperType();
		return superType != null ? "It extends from the \"" + superType.getJavaType().getTypeName() + "\" type.\n" : "";
	}

	private static <T> String getMappedSuperclassTypeDescription(MappedSuperclassType<T> mappedSuperclass) {
		return "\"" + mappedSuperclass.getJavaType()
				.getSimpleName() + "\" is a mapped superclass type.\n" + getJavaTypeDescription( mappedSuperclass ) + getInheritanceDescription(
				(ManagedDomainType<?>) mappedSuperclass ) + getIdentifierDescription( mappedSuperclass ) + getAttributesDescription(
				mappedSuperclass.getAttributes() );
	}

	private static <T> String getIdentifierDescription(IdentifiableType<T> identifiableType) {
		final Type<?> idType = identifiableType.getIdType();
		final String description;
		if ( idType != null ) {
			final SingularAttribute<? super T, ?> id = identifiableType.getId( idType.getJavaType() );
			description = "Its identifier attribute is called \"" + id.getName() + "\" and is of type \"" + id.getJavaType()
					.getTypeName() + "\".\n";
		}
		else {
			description = "It has no identifier attribute.\n";
		}
		return description;
	}

	private static <T> String getEmbeddableTypeDescription(EmbeddableType<T> embeddableType) {
		return "\"" + embeddableType.getJavaType()
				.getSimpleName() + "\" is an embeddable type.\n" + getInheritanceDescription( (ManagedDomainType<?>) embeddableType ) + getJavaTypeDescription(
				embeddableType ) + getAttributesDescription( embeddableType.getAttributes() );
	}

	private static <T> String getAttributesDescription(Set<Attribute<? super T, ?>> attributes) {
		final StringBuilder sb = new StringBuilder( "Its attributes are (name => type):\n" );
		for ( final Attribute<? super T, ?> attribute : attributes ) {
			sb.append( "- \"" ).append( attribute.getName() ).append( "\" => \"" ).append( attribute.getJavaType()
																								   .getTypeName() );

			// add key and element types for plural attributes
			if ( attribute instanceof PluralAttribute<?, ?, ?> pluralAttribute ) {
				sb.append( "<" );
				final PluralAttribute.CollectionType collectionType = pluralAttribute.getCollectionType();
				if ( collectionType == PluralAttribute.CollectionType.MAP ) {
					sb.append( ( (MapAttribute<?, ?, ?>) pluralAttribute ).getKeyJavaType().getTypeName() )
							.append( ", " );
				}
				sb.append( pluralAttribute.getElementType().getJavaType().getTypeName() ).append( ">" );
			}
			sb.append( "\"\n" );
		}
		return sb.toString();
	}
}
