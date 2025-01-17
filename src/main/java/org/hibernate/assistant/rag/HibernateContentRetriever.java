package org.hibernate.assistant.rag;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.assistant.AiQuery;
import org.hibernate.assistant.HibernateAssistant;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import org.jboss.logging.Logger;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public class HibernateContentRetriever implements ContentRetriever {
	private static final Logger log = Logger.getLogger( HibernateAssistant.class );

	/**
	 * Recommended default prompt template to use in conjuction with {@link HibernateContentRetriever},
	 * simply provide this to {@link dev.langchain4j.rag.content.injector.DefaultContentInjector}.
	 */
	public static final PromptTemplate INJECTOR_PROMPT_TEMPLATE = PromptTemplate.from(
			"""
					{{userMessage}}
					
					The query returned the following data:
					{{contents}}
					
					Answer the original question using natural language and do not create a query!"""
	);

	public static class Builder {
		private ChatLanguageModel chatModel;
		private ChatMemory chatMemory;
		private PromptTemplate metamodelPromptTemplate;
		private SessionFactory sessionFactory;

		public Builder chatModel(ChatLanguageModel chatModel) {
			this.chatModel = chatModel;
			return this;
		}

		public Builder chatMemory(ChatMemory chatMemory) {
			this.chatMemory = chatMemory;
			return this;
		}

		public Builder metamodelPromptTemplate(PromptTemplate metamodelPromptTemplate) {
			this.metamodelPromptTemplate = metamodelPromptTemplate;
			return this;
		}

		public Builder sessionFactory(SessionFactory sessionFactory) {
			this.sessionFactory = sessionFactory;
			return this;
		}

		public HibernateContentRetriever build() {
			return new HibernateContentRetriever( this );
		}
	}

	private final HibernateAssistant assistant;
	private final SessionFactoryImplementor sessionFactory;

	public HibernateContentRetriever(HibernateAssistant assistant, SessionFactory sessionFactory) {
		this.assistant = ensureNotNull( assistant, "Metamodel" );
		this.sessionFactory = (SessionFactoryImplementor) ensureNotNull( sessionFactory, "Session Factory" );
	}

	public HibernateContentRetriever(
			ChatLanguageModel chatModel,
			ChatMemory chatMemory,
			SessionFactory sessionFactory) {
		this(
				HibernateAssistant.builder()
						.chatModel( chatModel )
						.chatMemory( chatMemory )
						.metamodel( sessionFactory.getMetamodel() )
						.build(),
				sessionFactory
		);
	}

	public HibernateContentRetriever(
			ChatLanguageModel chatModel,
			ChatMemory chatMemory,
			PromptTemplate metamodelPromptTemplate,
			SessionFactory sessionFactory) {
		this(
				HibernateAssistant.builder()
						.chatModel( chatModel )
						.chatMemory( chatMemory )
						.metamodel( sessionFactory.getMetamodel() )
						.metamodelPromptTemplate( metamodelPromptTemplate )
						.build(),
				sessionFactory
		);
	}

	private HibernateContentRetriever(Builder builder) {
		this( builder.chatModel, builder.chatMemory, builder.metamodelPromptTemplate, builder.sessionFactory );
	}

	@Override
	public List<Content> retrieve(Query naturalLanguageQuery) {
		// todo we could implement retries, and pass the error message to the chat model
//		String errorMessage = null;
//		int attemptsLeft = maxRetries + 1;
//		while (attemptsLeft > 0) {
//			attemptsLeft--;

		final String result = sessionFactory.fromSession( session -> {
			final AiQuery<Object> aiQuery = assistant.createAiQuery( naturalLanguageQuery.text(), session );

			try {
				return assistant.executeQueryToString( aiQuery, session );
			}
			catch (Exception e) {
				log.errorf( e, "Error executing query, hql: %s", aiQuery.getHql() );
				return null;
			}
		} );

		return result == null ? emptyList() : singletonList( Content.from( result ) );
	}

	public static Builder builder() {
		return new Builder();
	}
}
