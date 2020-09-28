/**
 * 
 */
package org.meveo.persistence.graphql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 
 * @author clement.bareth
 * @since 6.11.0
 * @version 6.11.0
 */
public class GraphQLQueryBuilder {
	
	private final String type;
	private final Map<String, Object> filters = new HashMap<>();
	private final List<String> fields = new ArrayList<>();
	private final Map<String, GraphQLQueryBuilder> subQueries = new HashMap<>();
	private GraphQLQueryBuilder parent;
	
	public static GraphQLQueryBuilder create(String type) {
		return new GraphQLQueryBuilder(type);
	}
	
	private GraphQLQueryBuilder (String type) {
		this.type = type;
	}
	
	public GraphQLQueryBuilder filter(String name, Object value) {
		filters.put(name, value);
		return this;
	}
	
	public GraphQLQueryBuilder field(String name) {
		fields.add(name);
		return this;
	}
	
	public GraphQLQueryBuilder field(String name, GraphQLQueryBuilder value) {
		subQueries.put(name, value);
		value.parent = this;
		return this;
	}
	
	@Override
	public String toString() {
		StringBuilder tabs = new StringBuilder("\t\t");
		StringBuilder tabsMinus = new StringBuilder("\t");

		for(var i = parent; i != null; i = i.parent) {
			tabs.append("\t");
			tabsMinus.append("\t");
		}
		
		String filtersStr = filters.isEmpty() ? "" : filters.entrySet()
				.stream()
				.map(e -> { 
					if(e.getValue() instanceof String) {
						return e.getKey() + ":\"" + e.getValue() + "\"";
					} else {
						return e.getKey() + ":" + e.getValue();
					}
				}).collect(Collectors.joining(",", "(", ")"));
		
		List<String> fieldsList = new ArrayList<>(fields);
		fieldsList.addAll(
			subQueries.entrySet()
				.stream()
				.map(e -> e.getKey() + e.getValue().toString())
				.collect(Collectors.toList())
		);
		
		String fieldStr = fieldsList.stream()
				.collect(Collectors.joining("\n" + tabs.toString(), tabs.toString(), "\n" + tabsMinus.toString()));

		if(parent == null) {
			return " {\n\t" + type + " " + filtersStr + " {\n" + fieldStr + "}" + "\n}";
		} else {
			return " " + filtersStr + " {\n" + fieldStr  + "}";
		}
	}

}
